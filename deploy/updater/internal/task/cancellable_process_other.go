//go:build !linux

package task

import (
	"os"
	"os/exec"
	"time"
)

func configureCancellableCommand(cmd *exec.Cmd) {
	cmd.Cancel = func() error {
		if cmd.Process == nil {
			return os.ErrProcessDone
		}
		return cmd.Process.Signal(os.Interrupt)
	}
	cmd.WaitDelay = 20 * time.Second
}
