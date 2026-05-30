# Maven Index Server v2

轻量级 Maven Central 版本索引系统。当前只支持 **Java 25 builder + v2 SQLite schema + Go 只读查询服务**，不保留 v1/Kotlin 兼容层。

## 架构

```
Maven Central packed index.gz
        |
        v
Java 25 builder
  - packed parser
  - binary shard aggregation
  - SQLite FTS5
  - zstd binary versions_blob
        |
        v
maven-index.db + metadata.json
        |
        v
Go API server
  - pure-Go SQLite driver
  - readonly + mmap
  - hot reload
```

## 空间与资源

本项目在 2026-05-29 的当前索引上实测：

| 项目 | 实测大小/用量 |
|---|---:|
| 原始 `nexus-maven-repository-index.gz` | `2.81 GiB` |
| 生成的 `maven-index.db` | `811 MiB` |
| 全量 source records | `101,053,668` |
| 唯一 artifacts | `821,657` |
| 去重版本数 | `20,181,184` |
| 本机全量构建时间 | 约 `2 分钟` |
| Builder JVM 内存目标 | `-Xmx2g` |
| 推荐本地可用空间 | `16 GiB+` |
| 推荐 CI 工作盘 | `12-16 GiB+` |

GitHub 标准 runner 当前 SSD 空间约 `14 GB`，公有仓库 Linux runner 为 `4 CPU / 16 GB RAM`，私有仓库 Linux runner 为 `2 CPU / 8 GB RAM`。空间紧张时使用 self-hosted runner 或 GitHub larger runner。

不要把 DB 或 index.gz 提交到 Git 仓库。GitHub 普通仓库会阻止超过 `100 MiB` 的文件，GitHub 也建议大二进制用 Release 分发；Actions artifact 存储额度在 Free/Pro/Team 上较小，当前 DB 已接近或超过部分额度。

参考：
- [GitHub-hosted runner resources](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)
- [GitHub Actions limits](https://docs.github.com/en/actions/reference/limits)
- [GitHub large files](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github)

## 构建端

### 环境

- JDK 25
- Maven 3.9+

本机 JDK 25：

```powershell
$env:JAVA_HOME="D:\env\java\jdk\jvms\store\jdk25"
$env:Path="$env:JAVA_HOME\bin;D:\env\java\maven\apache-maven-3.9.10\bin;$env:Path"
```

### 编译

```bash
cd builder
mvn clean package
```

### 全量构建 DB

```bash
java -Xmx2g -cp "builder/target/maven-index-builder-2.0.0.jar:builder/target/lib/*" \
  com.example.mavenindex.BuilderMain \
  --source ./nexus-maven-repository-index.gz \
  --output ./data/releases/20260529 \
  --shards 512 \
  --workers 4
```

Windows PowerShell：

```powershell
java -Xmx2g -cp "builder\target\maven-index-builder-2.0.0.jar;builder\target\lib\*" `
  com.example.mavenindex.BuilderMain `
  --source .\nexus-maven-repository-index.gz `
  --output .\data\releases\20260529 `
  --shards 512 `
  --workers 4
```

常用参数：

| 参数 | 说明 |
|---|---|
| `--source` | packed `nexus-maven-repository-index.gz` 路径；默认当前目录同名文件 |
| `--output` | 输出目录，包含 `maven-index.db` 和 `metadata.json` |
| `--shards` | 二进制分片数，必须是 2 的幂，默认 `512` |
| `--workers` | 聚合 worker 数，默认按 CPU 保守计算 |
| `--max-records` | smoke test 用，限制读取 source record 数 |
| `--enable-trigram` | 可选 trigram FTS 表，默认关闭 |

Builder 产出的 `metadata.json` 包含 `schemaVersion=2`、`blobFormat=miv2-zstd-bin`、artifact/version counts、构建耗时、DB 大小、source 路径、`sourceSha256` 和 `sourceSizeBytes`。GitHub Actions 发版前会额外补齐远端 `sourceSha1`、`sourceUrl`、`builderTreeHash` 和 `configHash`。

## 查询端

### 环境

- Go 1.25+
- 不需要 gcc，不需要 CGO。服务使用 `modernc.org/sqlite` pure-Go SQLite driver。

本机 Go：

```powershell
& "D:\Program Files\Go\bin\go.exe" version
```

### 编译

```bash
cd server
go test ./...
go build -o maven-index-server .
```

Windows PowerShell：

```powershell
cd server
& "D:\Program Files\Go\bin\go.exe" test ./...
& "D:\Program Files\Go\bin\go.exe" build -o maven-index-server.exe .
```

### 运行

```bash
./maven-index-server -dir /data/maven-index -port 8080
```

或直接指定 DB：

```bash
./maven-index-server -db /data/maven-index/current/maven-index.db -port 8080
```

### Web 搜索页

服务内置一个轻量搜索页面：

```http
GET /
```

页面使用现有 `/api/maven/search` 和 `/api/maven/versions`，提供 artifact 搜索、版本列表，以及 Maven/Gradle 坐标复制。

## API

### 健康检查

```http
GET /health
```

### 搜索

```http
GET /api/maven/search?q=spring boot&limit=20
```

搜索策略：

1. exact `ga`
2. normalized exact `ga`
3. exact `artifact_id`
4. normalized exact `artifact_id`
5. FTS5

### 版本列表

```http
GET /api/maven/versions?groupId=org.springframework.boot&artifactId=spring-boot-starter-web
```

### 版本检查

```http
POST /api/maven/check
Content-Type: application/json

{
  "dependencies": [
    {"groupId":"org.springframework.boot","artifactId":"spring-boot-starter-web","version":"2.7.18"}
  ]
}
```

### 热重载

```http
POST /admin/reload
```

## GitHub Release 分发

推荐架构：

```
GitHub Actions build-index.yml
  -> 读取 Maven Central index.gz.sha1 或 X-Checksum-SHA1
  -> 对比最近 index release 的 metadata.json
  -> checksum/configHash 未变化则跳过
  -> 变化后下载 Maven Central index.gz 并校验 SHA1
  -> Java builder 生成 maven-index.db
  -> 打包 maven-index-YYYYMMDD-HHMMSS.tar.zst
  -> 发布 GitHub Release assets

Server scripts/pull-index.sh
  -> 下载 latest release
  -> 校验 SHA256SUMS
  -> 校验 metadata hash 字段
  -> 解压到 releases/<release-id>
  -> 原子切换 current
  -> POST /admin/reload
```

Release 包格式：

```text
maven-index-YYYYMMDD-HHMMSS.tar.zst
├── maven-index.db
├── metadata.json
└── SHA256SUMS
```

Release 页面额外上传外层 `SHA256SUMS` 和独立 `metadata.json`。外层 `SHA256SUMS` 用于先校验 `.tar.zst` 本身；独立 `metadata.json` 供下一次 workflow 在下载 3GB source 前判断是否需要重建。

### GitHub Actions

工作流在 `.github/workflows/build-index.yml`：

- 触发：`workflow_dispatch` 和每 6 小时定时。
- 默认 runner：`ubuntu-24.04`。
- 先读 `nexus-maven-repository-index.gz.sha1`，fallback 到 HEAD header 的 `X-Checksum-SHA1`，不先下载大文件。
- `configHash = sha256(sourceUrl + sourceSha1 + schemaVersion + blobFormat + enableTrigram + maxRecords + builderTreeHash)`。
- `builderTreeHash` 只覆盖 `builder/pom.xml` 和 `builder/src`，server/UI 改动不会触发 DB 重建。
- 发布 tag：`index-YYYYMMDD-HHMMSS`。
- 默认只保留最近 `5` 个 index releases。

### 分发方案取舍

当前推荐 GitHub Releases：接入简单，版本可追溯，`gh release download` 可直接拉取。后续如果单个 asset 或访问量压力变大，可以改为 GHCR/OCI artifact（用 `oras` 拉取）或 Cloudflare R2/S3 + `latest.json` manifest。Actions artifact 和 Git LFS 不适合作为这个周期性大 DB 的长期分发方案。

### 服务器拉取

```bash
scripts/pull-index.sh \
  --repo OWNER/REPO \
  --base-dir /data/maven-index \
  --reload-url http://localhost:8080/admin/reload
```

私有仓库先配置 GitHub CLI：

```bash
gh auth login
```

或设置 `GH_TOKEN` 后运行脚本。

## SQLite v2 Schema

核心表：

```sql
CREATE TABLE meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE artifacts (
    id INTEGER PRIMARY KEY,
    ga TEXT NOT NULL UNIQUE,
    group_id TEXT NOT NULL,
    artifact_id TEXT NOT NULL,
    artifact_id_norm TEXT NOT NULL,
    group_id_norm TEXT NOT NULL,
    ga_norm TEXT NOT NULL,
    search_text TEXT NOT NULL,
    latest_version TEXT,
    latest_stable_version TEXT,
    version_count INTEGER NOT NULL DEFAULT 0,
    last_timestamp INTEGER,
    versions_blob BLOB NOT NULL
);
```

`versions_blob` 格式固定为 `miv2-zstd-bin`。Go 服务启动时会校验 `schema_version=2` 和 `blob_format=miv2-zstd-bin`。
