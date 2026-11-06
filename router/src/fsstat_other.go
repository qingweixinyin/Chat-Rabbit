//go:build !linux

package main

// fsStat 非 Linux 平台（仅本机冒烟测试用）：不支持文件系统统计。
func fsStat(p string) (int64, int64) { return 0, 0 }
