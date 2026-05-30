package main

import (
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestNormalizeForQuery(t *testing.T) {
	got := normalizeForQuery("org.springframework.boot:spring-boot_starter-web")
	want := "orgspringframeworkbootspringbootstarterweb"
	if got != want {
		t.Fatalf("normalizeForQuery() = %q, want %q", got, want)
	}
}

func TestPrefixUpperBound(t *testing.T) {
	tests := map[string]string{
		"springboot": "springboou",
		"a":          "b",
		"":           "",
	}
	for input, want := range tests {
		if got := prefixUpperBound(input); got != want {
			t.Fatalf("prefixUpperBound(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestBuildFTSQuery(t *testing.T) {
	got := buildFTSQuery(`spring "boot"`)
	want := `"spring"* OR """boot"""*`
	if got != want {
		t.Fatalf("buildFTSQuery() = %q, want %q", got, want)
	}
}

func TestSortResults(t *testing.T) {
	list := []searchResult{
		{GroupID: "b", ArtifactID: "b", Score: 100, VersionCount: 1},
		{GroupID: "a", ArtifactID: "a", Score: 500, VersionCount: 1},
		{GroupID: "c", ArtifactID: "c", Score: 500, VersionCount: 10},
	}
	sortResults(list)
	got := []string{list[0].GroupID, list[1].GroupID, list[2].GroupID}
	want := []string{"c", "a", "b"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("sort order = %v, want %v", got, want)
	}
}

func TestResolveDBPath(t *testing.T) {
	if got := resolveDBPath("/base", "/direct/db.sqlite"); got != "/direct/db.sqlite" {
		t.Fatalf("direct db path = %q", got)
	}
	want := filepath.Join("/base", "current", "maven-index.db")
	if got := resolveDBPath("/base", ""); got != want {
		t.Fatalf("base db path = %q", got)
	}
}

func TestEmbeddedIndexPage(t *testing.T) {
	page, err := embeddedStatic.ReadFile("static/index.html")
	if err != nil {
		t.Fatalf("read embedded index page: %v", err)
	}
	if !strings.Contains(string(page), "/static/app.js") {
		t.Fatalf("embedded index page does not reference app.js")
	}
}
