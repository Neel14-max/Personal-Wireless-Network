#!/bin/bash
# TetherDirect - Linux Disconnect
IFACE="${1:-wlan0}"
wpa_cli -i $IFACE terminate 2>/dev/null; killall wpa_supplicant 2>/dev/null
ip addr flush dev $IFACE 2>/dev/null
systemctl start NetworkManager 2>/dev/null || true
echo "Disconnected."
