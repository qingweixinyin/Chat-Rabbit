//go:build linux

package main

import "syscall"

// fsStat 返回路径所在文件系统的总容量与可用容量（字节）。Linux 用 statfs。
func fsStat(p string) (int64, int64) {
	var fs syscall.Statfs_t
	if err := syscall.Statfs(p, &fs); err != nil {
		return 0, 0
	}
	return int64(fs.Blocks) * int64(fs.Bsize), int64(fs.Bavail) * int64(fs.Bsize)
}
