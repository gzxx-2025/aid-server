package artifact

import (
	"archive/tar"
	"compress/gzip"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

const (
	maxExtractedBytes = int64(8 << 30)
	maxArchiveEntries = 100000
)

// ExtractTarGz 解压 tar.gz 到 dst 目录，拒绝路径穿越与符号链接。
func ExtractTarGz(src, dst string) error {
	f, err := os.Open(src)
	if err != nil {
		return fmt.Errorf("打开压缩包失败: %w", err)
	}
	defer f.Close()

	gz, err := gzip.NewReader(f)
	if err != nil {
		return fmt.Errorf("读取gzip失败: %w", err)
	}
	defer gz.Close()

	if err := os.MkdirAll(dst, 0o755); err != nil {
		return fmt.Errorf("创建解压目录失败: %w", err)
	}
	cleanDst := filepath.Clean(dst)

	reader := tar.NewReader(gz)
	var extractedBytes int64
	entryCount := 0
	for {
		header, err := reader.Next()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return fmt.Errorf("读取tar条目失败: %w", err)
		}
		entryCount++
		if entryCount > maxArchiveEntries {
			return fmt.Errorf("压缩包条目数量超过上限")
		}
		target := filepath.Join(cleanDst, filepath.FromSlash(header.Name))
		// 防路径穿越：解压目标必须落在 dst 内
		if target != cleanDst && !strings.HasPrefix(target, cleanDst+string(os.PathSeparator)) {
			return fmt.Errorf("压缩包含非法路径: %s", header.Name)
		}
		switch header.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, 0o755); err != nil {
				return fmt.Errorf("创建目录失败: %w", err)
			}
		case tar.TypeReg:
			if exceedsExtractLimit(header.Size, extractedBytes) {
				return fmt.Errorf("压缩包解压大小超过上限")
			}
			if err := writeRegularFile(reader, target, header.FileInfo().Mode(), header.Size); err != nil {
				return err
			}
			extractedBytes += header.Size
		default:
			// 符号链接等特殊类型一律跳过，升级包不应包含
			continue
		}
	}
}

func exceedsExtractLimit(size, extracted int64) bool {
	return size < 0 || extracted < 0 || extracted > maxExtractedBytes || size > maxExtractedBytes-extracted
}

func writeRegularFile(r io.Reader, target string, mode os.FileMode, size int64) error {
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return fmt.Errorf("创建父目录失败: %w", err)
	}
	out, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, mode.Perm())
	if err != nil {
		return fmt.Errorf("创建文件失败: %w", err)
	}
	defer out.Close()
	written, err := io.CopyN(out, r, size)
	if err != nil {
		return fmt.Errorf("写文件失败: %w", err)
	}
	if written != size {
		return fmt.Errorf("文件大小不完整")
	}
	return nil
}
