param([string]$Marker)
# Stops java processes whose command line contains the marker (e.g. parity-tpl), and waits for exit.
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match $Marker } | ForEach-Object {
  Stop-Process -Id $_.ProcessId -Force
  Wait-Process -Id $_.ProcessId -Timeout 10 -ErrorAction SilentlyContinue
}
