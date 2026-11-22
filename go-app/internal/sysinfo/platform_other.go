//go:build !windows

package sysinfo

func platformOS() *OsInfo             { return nil }
func platformGPUs() []GpuInfo         { return nil }
func platformDecoders() *[]Codec      { return nil }
func platformDisplays() []DisplayInfo { return nil }
