package main

import (
	"database/sql"
	"embed"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync/atomic"
	"time"

	"github.com/gin-gonic/gin"
	_ "modernc.org/sqlite"
)

const sqliteDriverName = "sqlite"

type dbHolder struct {
	db       *sql.DB
	dbPath   string
	artifact int64
	queries  preparedQueries
}

type preparedQueries struct {
	exactGA             *sql.Stmt
	exactGANorm         *sql.Stmt
	exactArtifactID     *sql.Stmt
	exactArtifactIDNorm *sql.Stmt
	fts                 *sql.Stmt
	versions            *sql.Stmt
}

var active atomic.Pointer[dbHolder]

//go:embed static/*
var embeddedStatic embed.FS

func main() {
	baseDir := flag.String("dir", "/data/maven-index", "Base directory with current/ symlink")
	dbFile := flag.String("db", "", "Direct path to .db (overrides -dir/current)")
	port := flag.Int("port", 8080, "HTTP listen port")
	flag.Parse()

	dbPath := resolveDBPath(*baseDir, *dbFile)
	if err := loadDB(dbPath); err != nil {
		log.Fatalf("Failed to load database: %v", err)
	}

	gin.SetMode(gin.ReleaseMode)
	r := gin.New()
	r.Use(gin.Recovery(), requestLogger())

	staticFS, err := fs.Sub(embeddedStatic, "static")
	if err != nil {
		log.Fatalf("Static assets: %v", err)
	}
	indexHTML, err := embeddedStatic.ReadFile("static/index.html")
	if err != nil {
		log.Fatalf("Read index page: %v", err)
	}

	r.GET("/", func(c *gin.Context) {
		c.Data(http.StatusOK, "text/html; charset=utf-8", indexHTML)
	})
	r.StaticFS("/static", http.FS(staticFS))
	r.GET("/health", healthHandler)
	r.GET("/api/maven/search", searchHandler)
	r.GET("/api/maven/versions", versionsHandler)
	r.POST("/api/maven/check", checkHandler)
	r.POST("/admin/reload", reloadHandler(*baseDir, *dbFile))

	log.Printf("Listening on :%d (db=%s)", *port, dbPath)
	if err := r.Run(fmt.Sprintf(":%d", *port)); err != nil {
		log.Fatalf("Server: %v", err)
	}
}

func loadDB(path string) error {
	absPath, err := filepath.Abs(path)
	if err != nil {
		return fmt.Errorf("resolve path: %w", err)
	}
	if _, err := os.Stat(absPath); os.IsNotExist(err) {
		return fmt.Errorf("database not found: %s", absPath)
	}

	dsnPath := filepath.ToSlash(absPath)
	if runtime.GOOS == "windows" && !strings.HasPrefix(dsnPath, "/") {
		dsnPath = "/" + dsnPath
	}
	dbURL := url.URL{Scheme: "file", Path: dsnPath}
	params := url.Values{}
	params.Set("mode", "ro")
	params.Set("immutable", "1")
	params.Set("cache", "shared")
	params.Add("_pragma", "query_only(ON)")
	params.Add("_pragma", "mmap_size(268435456)")
	params.Add("_pragma", "cache_size(-20000)")
	params.Add("_pragma", "temp_store(MEMORY)")
	params.Add("_pragma", "busy_timeout(5000)")
	dbURL.RawQuery = params.Encode()
	dsn := dbURL.String()

	db, err := sql.Open(sqliteDriverName, dsn)
	if err != nil {
		return fmt.Errorf("open db: %w", err)
	}
	db.SetMaxOpenConns(minInt(8, maxInt(2, runtime.GOMAXPROCS(0))))
	db.SetMaxIdleConns(minInt(4, maxInt(1, runtime.GOMAXPROCS(0)/2)))

	if err := verifySchema(db); err != nil {
		db.Close()
		return err
	}

	var count int64
	if err := db.QueryRow("SELECT count(*) FROM artifacts").Scan(&count); err != nil {
		db.Close()
		return fmt.Errorf("count artifacts: %w", err)
	}

	queries, err := prepareQueries(db)
	if err != nil {
		db.Close()
		return err
	}

	holder := &dbHolder{db: db, dbPath: absPath, artifact: count, queries: queries}
	old := active.Swap(holder)
	if old != nil {
		old.close()
	}

	log.Printf("Loaded %s: %d artifacts", absPath, count)
	return nil
}

func verifySchema(db *sql.DB) error {
	values := map[string]string{}
	rows, err := db.Query("SELECT key, value FROM meta WHERE key IN ('schema_version', 'blob_format')")
	if err != nil {
		return fmt.Errorf("read meta: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		var key, value string
		if err := rows.Scan(&key, &value); err != nil {
			return fmt.Errorf("scan meta: %w", err)
		}
		values[key] = value
	}
	if err := rows.Err(); err != nil {
		return fmt.Errorf("read meta rows: %w", err)
	}
	if values["schema_version"] != "2" {
		return fmt.Errorf("unsupported schema_version %q, need 2", values["schema_version"])
	}
	if values["blob_format"] != versionBlobFormat {
		return fmt.Errorf("unsupported blob_format %q, need %s", values["blob_format"], versionBlobFormat)
	}
	return nil
}

func prepareQueries(db *sql.DB) (preparedQueries, error) {
	var q preparedQueries
	var err error
	if q.exactGA, err = db.Prepare(`
		SELECT group_id, artifact_id, latest_version, latest_stable_version, version_count
		FROM artifacts WHERE ga = ?`); err != nil {
		return q, err
	}
	if q.exactGANorm, err = db.Prepare(`
		SELECT group_id, artifact_id, latest_version, latest_stable_version, version_count
		FROM artifacts WHERE ga_norm = ?`); err != nil {
		q.close()
		return q, err
	}
	if q.exactArtifactID, err = db.Prepare(`
		SELECT group_id, artifact_id, latest_version, latest_stable_version, version_count
		FROM artifacts WHERE artifact_id = ?
		ORDER BY version_count DESC
		LIMIT ?`); err != nil {
		q.close()
		return q, err
	}
	if q.exactArtifactIDNorm, err = db.Prepare(`
		SELECT group_id, artifact_id, latest_version, latest_stable_version, version_count
		FROM artifacts WHERE artifact_id_norm = ?
		ORDER BY version_count DESC
		LIMIT ?`); err != nil {
		q.close()
		return q, err
	}
	if q.fts, err = db.Prepare(`
		SELECT a.group_id, a.artifact_id, a.latest_version, a.latest_stable_version,
		       a.version_count, rank
		FROM artifact_fts
		JOIN artifacts a ON a.id = artifact_fts.rowid
		WHERE artifact_fts MATCH ?
		ORDER BY rank
		LIMIT ?`); err != nil {
		q.close()
		return q, err
	}
	if q.versions, err = db.Prepare(`
		SELECT versions_blob, latest_version, latest_stable_version, version_count
		FROM artifacts WHERE group_id = ? AND artifact_id = ?`); err != nil {
		q.close()
		return q, err
	}
	return q, nil
}

func (q preparedQueries) close() {
	for _, stmt := range []*sql.Stmt{
		q.exactGA,
		q.exactGANorm,
		q.exactArtifactID,
		q.exactArtifactIDNorm,
		q.fts,
		q.versions,
	} {
		if stmt != nil {
			stmt.Close()
		}
	}
}

func (h *dbHolder) close() {
	h.queries.close()
	h.db.Close()
}

func requestLogger() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()
		log.Printf("%s %s %d %s", c.Request.Method, c.Request.URL.Path,
			c.Writer.Status(), time.Since(start))
	}
}

func healthHandler(c *gin.Context) {
	h := active.Load()
	c.JSON(http.StatusOK, gin.H{
		"status":    "ok",
		"artifacts": h.artifact,
		"db":        h.dbPath,
		"time":      time.Now().Format(time.RFC3339),
	})
}

func reloadHandler(baseDir string, directDB string) gin.HandlerFunc {
	return func(c *gin.Context) {
		if err := loadDB(resolveDBPath(baseDir, directDB)); err != nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{
				"error":   "reload failed",
				"details": err.Error(),
			})
			return
		}
		h := active.Load()
		c.JSON(http.StatusOK, gin.H{
			"status":    "reloaded",
			"db":        h.dbPath,
			"artifacts": h.artifact,
		})
	}
}

type searchResult struct {
	GroupID             string  `json:"groupId"`
	ArtifactID          string  `json:"artifactId"`
	LatestVersion       *string `json:"latestVersion"`
	LatestStableVersion *string `json:"latestStableVersion"`
	VersionCount        int     `json:"versionCount"`
	Score               float64 `json:"-"`
}

func searchHandler(c *gin.Context) {
	q := strings.TrimSpace(c.Query("q"))
	if q == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "q is required"})
		return
	}
	limit := parseInt(c.Query("limit"), 20)
	if limit > 100 {
		limit = 100
	}

	h := active.Load()
	results := make(map[string]*searchResult, limit*3)
	qNorm := normalizeForQuery(q)

	querySearchRows(h.queries.exactGA, 1000, results, q)
	if qNorm != "" && qNorm != q {
		querySearchRows(h.queries.exactGANorm, 950, results, qNorm)
	}
	if len(results) < limit {
		querySearchRows(h.queries.exactArtifactID, 500, results, q, limit*2)
	}
	if len(results) < limit && qNorm != "" && qNorm != q {
		querySearchRows(h.queries.exactArtifactIDNorm, 450, results, qNorm, limit*2)
	}
	if len(results) < limit {
		ftsSearch(h.queries.fts, q, limit, results)
	}

	list := make([]searchResult, 0, len(results))
	for _, r := range results {
		list = append(list, *r)
	}
	sortResults(list)
	if len(list) > limit {
		list = list[:limit]
	}

	c.JSON(http.StatusOK, gin.H{
		"query":   q,
		"total":   len(list),
		"results": list,
	})
}

func querySearchRows(stmt *sql.Stmt, score float64, results map[string]*searchResult, args ...any) {
	rows, err := stmt.Query(args...)
	if err != nil {
		return
	}
	defer rows.Close()
	collectSearchRows(rows, score, results)
}

func collectSearchRows(rows *sql.Rows, score float64, results map[string]*searchResult) {
	for rows.Next() {
		r := &searchResult{Score: score}
		if err := rows.Scan(&r.GroupID, &r.ArtifactID, &r.LatestVersion, &r.LatestStableVersion, &r.VersionCount); err != nil {
			continue
		}
		key := r.GroupID + ":" + r.ArtifactID
		if existing, exists := results[key]; !exists || r.Score > existing.Score {
			results[key] = r
		}
	}
}

func ftsSearch(stmt *sql.Stmt, q string, limit int, results map[string]*searchResult) {
	ftsQuery := buildFTSQuery(q)
	rows, err := stmt.Query(ftsQuery, limit*3)
	if err != nil {
		return
	}
	defer rows.Close()
	for rows.Next() {
		r := &searchResult{}
		var rank float64
		if err := rows.Scan(&r.GroupID, &r.ArtifactID, &r.LatestVersion, &r.LatestStableVersion,
			&r.VersionCount, &rank); err != nil {
			continue
		}
		r.Score = 100 - rank
		key := r.GroupID + ":" + r.ArtifactID
		if existing, exists := results[key]; !exists || r.Score > existing.Score {
			results[key] = r
		}
	}
}

func buildFTSQuery(q string) string {
	parts := strings.Fields(q)
	if len(parts) == 0 {
		return q
	}
	quoted := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		p = strings.ReplaceAll(p, `"`, `""`)
		quoted = append(quoted, fmt.Sprintf(`"%s"*`, p))
	}
	if len(quoted) == 0 {
		return q
	}
	return strings.Join(quoted, " OR ")
}

func sortResults(list []searchResult) {
	sort.Slice(list, func(i, j int) bool {
		if list[i].Score != list[j].Score {
			return list[i].Score > list[j].Score
		}
		if list[i].VersionCount != list[j].VersionCount {
			return list[i].VersionCount > list[j].VersionCount
		}
		return list[i].GroupID+":"+list[i].ArtifactID < list[j].GroupID+":"+list[j].ArtifactID
	})
}

func versionsHandler(c *gin.Context) {
	groupID := c.Query("groupId")
	artifactID := c.Query("artifactId")
	if groupID == "" || artifactID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "groupId and artifactId are required"})
		return
	}

	h := active.Load()
	var blob []byte
	var latestVersion, latestStable *string
	var versionCount int
	err := h.queries.versions.QueryRow(groupID, artifactID).Scan(&blob, &latestVersion, &latestStable, &versionCount)
	if err == sql.ErrNoRows {
		c.JSON(http.StatusNotFound, gin.H{"error": "artifact not found"})
		return
	}
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	versions, err := decodeVersionsBlob(blob)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "parse versions failed"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"groupId":             groupID,
		"artifactId":          artifactID,
		"latestVersion":       latestVersion,
		"latestStableVersion": latestStable,
		"versionCount":        versionCount,
		"versions":            versions,
	})
}

type dependency struct {
	GroupID    string `json:"groupId"`
	ArtifactID string `json:"artifactId"`
	Version    string `json:"version"`
}

type checkItem struct {
	GroupID             string `json:"groupId"`
	ArtifactID          string `json:"artifactId"`
	CurrentVersion      string `json:"currentVersion"`
	LatestVersion       string `json:"latestVersion"`
	LatestStableVersion string `json:"latestStableVersion"`
	Exists              bool   `json:"exists"`
	UpgradeAvailable    bool   `json:"upgradeAvailable"`
}

type latestInfo struct {
	latestVersion string
	latestStable  string
	exists        bool
}

func checkHandler(c *gin.Context) {
	var req struct {
		Dependencies []dependency `json:"dependencies"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	h := active.Load()
	latestByGA := fetchLatestBatch(h.db, req.Dependencies)
	items := make([]checkItem, 0, len(req.Dependencies))

	for _, dep := range req.Dependencies {
		info := latestByGA[dep.GroupID+":"+dep.ArtifactID]
		item := checkItem{
			GroupID:        dep.GroupID,
			ArtifactID:     dep.ArtifactID,
			CurrentVersion: dep.Version,
			Exists:         info.exists,
		}
		if info.exists {
			item.LatestVersion = info.latestVersion
			item.LatestStableVersion = info.latestStable
			if item.LatestStableVersion != "" && item.LatestStableVersion != dep.Version {
				item.UpgradeAvailable = true
			} else if item.LatestVersion != "" && item.LatestVersion != dep.Version {
				item.UpgradeAvailable = true
			}
		}
		items = append(items, item)
	}

	c.JSON(http.StatusOK, gin.H{"items": items})
}

func fetchLatestBatch(db *sql.DB, deps []dependency) map[string]latestInfo {
	result := make(map[string]latestInfo, len(deps))
	seen := make(map[string]struct{}, len(deps))
	keys := make([]string, 0, len(deps))
	for _, dep := range deps {
		key := dep.GroupID + ":" + dep.ArtifactID
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		keys = append(keys, key)
	}

	const chunkSize = 500
	for start := 0; start < len(keys); start += chunkSize {
		end := minInt(start+chunkSize, len(keys))
		chunk := keys[start:end]
		placeholders := make([]string, len(chunk))
		args := make([]any, len(chunk))
		for i, key := range chunk {
			placeholders[i] = "?"
			args[i] = key
		}
		query := fmt.Sprintf(`
			SELECT ga, latest_version, latest_stable_version
			FROM artifacts WHERE ga IN (%s)`, strings.Join(placeholders, ","))
		rows, err := db.Query(query, args...)
		if err != nil {
			continue
		}
		for rows.Next() {
			var ga string
			var latest, stable sql.NullString
			if err := rows.Scan(&ga, &latest, &stable); err != nil {
				continue
			}
			info := latestInfo{exists: true}
			if latest.Valid {
				info.latestVersion = latest.String
			}
			if stable.Valid {
				info.latestStable = stable.String
			}
			result[ga] = info
		}
		rows.Close()
	}
	return result
}

func normalizeForQuery(input string) string {
	lower := strings.ToLower(input)
	var b strings.Builder
	b.Grow(len(lower))
	for _, r := range lower {
		if r != '.' && r != '-' && r != '_' && r != ':' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func resolveDBPath(baseDir string, directDB string) string {
	if directDB != "" {
		return directDB
	}
	return filepath.Join(baseDir, "current", "maven-index.db")
}

func parseInt(s string, def int) int {
	var n int
	if _, err := fmt.Sscanf(s, "%d", &n); err == nil && n > 0 {
		return n
	}
	return def
}

func minInt(a int, b int) int {
	if a < b {
		return a
	}
	return b
}

func maxInt(a int, b int) int {
	if a > b {
		return a
	}
	return b
}
