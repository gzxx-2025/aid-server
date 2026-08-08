package task

import (
	"bufio"
	"fmt"
	"io"
	"net"
	"testing"
)

func TestSelectRedisDatabaseUsesRESPSelect(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	expected := "*2\r\n$6\r\nSELECT\r\n$1\r\n3\r\n"
	serverResult := make(chan error, 1)
	go func() {
		request := make([]byte, len(expected))
		if _, err := io.ReadFull(server, request); err != nil {
			serverResult <- err
			return
		}
		if string(request) != expected {
			serverResult <- fmt.Errorf("unexpected command: %q", string(request))
			return
		}
		_, err := server.Write([]byte("+OK\r\n"))
		serverResult <- err
	}()
	if err := selectRedisDatabase(client, bufio.NewReader(client), "3"); err != nil {
		t.Fatalf("SELECT command failed: %v", err)
	}
	if err := <-serverResult; err != nil {
		t.Fatal(err)
	}
}
