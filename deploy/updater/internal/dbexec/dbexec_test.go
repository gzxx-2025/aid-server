package dbexec

import (
	"sort"
	"strings"
	"testing"

	"aid-updater/internal/config"
)

func TestBuildDBCommandUsesEphemeralClientForExternalDockerMySQL(t *testing.T) {
	db := config.Database{
		Password:      "databaseSecret",
		ClientImage:   "mysql:5.7",
		DockerNetwork: "host",
	}
	cmd := buildDBCommand(db, "mysql", "--host", "db.internal", "--port", "3306")
	joined := strings.Join(cmd.Args, " ")
	for _, expected := range []string{"docker run", "--rm", "--network host", "mysql:5.7 mysql", "--host db.internal"} {
		if !strings.Contains(joined, expected) {
			t.Fatalf("command is missing %q: %s", expected, joined)
		}
	}
	if strings.Contains(joined, db.Password) {
		t.Fatalf("database password leaked into command arguments: %s", joined)
	}
}

func TestBuildDBCommandPrefersInternalContainer(t *testing.T) {
	db := config.Database{Password: "rootSecret", ExecContainer: "aid-mysql", ClientImage: "mysql:5.7"}
	cmd := buildDBCommand(db, "mysqldump", "aid")
	joined := strings.Join(cmd.Args, " ")
	if !strings.Contains(joined, "docker exec -i -e MYSQL_PWD aid-mysql mysqldump aid") {
		t.Fatalf("unexpected internal database command: %s", joined)
	}
}

func TestMigrationScriptOrderFollowsReleaseSemver(t *testing.T) {
	scripts := []string{
		"v2.1.0.sql",
		"v1.0.0.sql",
		"v1.0.0-rc.2.sql",
		"v1.0.0-beta.10.sql",
		"v1.0.1.sql",
		"v1.0.0-beta.2.sql",
	}
	sort.SliceStable(scripts, func(i, j int) bool {
		return migrationScriptLess(scripts[i], scripts[j])
	})
	want := []string{
		"v1.0.0-beta.2.sql",
		"v1.0.0-beta.10.sql",
		"v1.0.0-rc.2.sql",
		"v1.0.0.sql",
		"v1.0.1.sql",
		"v2.1.0.sql",
	}
	for index := range want {
		if scripts[index] != want[index] {
			t.Fatalf("unexpected migration order at %d: got %q want %q (all=%v)", index, scripts[index], want[index], scripts)
		}
	}
}

func TestMigrationScriptOrderKeepsLegacyNamesAfterVersionedScripts(t *testing.T) {
	scripts := []string{"custom.sql", "v1.0.0.sql", "v1.0.0-beta.1.sql"}
	sort.SliceStable(scripts, func(i, j int) bool {
		return migrationScriptLess(scripts[i], scripts[j])
	})
	want := []string{"v1.0.0-beta.1.sql", "v1.0.0.sql", "custom.sql"}
	for index := range want {
		if scripts[index] != want[index] {
			t.Fatalf("unexpected migration order: got %v want %v", scripts, want)
		}
	}
}
