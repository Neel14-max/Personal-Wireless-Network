#!/bin/bash
# TetherDirect - Linux Connect
#
# Joins the phone's Wi-Fi Direct group and verifies the phone's SOCKS5 proxy is
# reachable. The phone is a SOCKS5 proxy (no root / no NAT on the phone), so
# after this runs, point your apps at socks5://192.168.49.1:8888.
set -e
SSID="$1"; PASSWORD="$2"; IFACE="${3:-wlan0}"
GATEWAY="192.168.49.1"; PROXY_PORT="8888"
[ "$EUID" -ne 0 ] && echo "Run with sudo" && exit 1
[ -z "$SSID" ] && echo "Usage: sudo bash connect.sh <SSID> <PASSWORD> [interface]" && exit 1

echo "Connecting to $SSID on $IFACE..."
killall wpa_supplicant 2>/dev/null || true; sleep 1
ip link set "$IFACE" up; sleep 2
cat > /tmp/tetherdirect.conf << EOF
ctrl_interface=/var/run/wpa_supplicant
network={ ssid="$SSID"; psk="$PASSWORD"; key_mgmt=WPA-PSK; proto=RSN; pairwise=CCMP; scan_ssid=1; }
EOF
wpa_supplicant -B -i "$IFACE" -c /tmp/tetherdirect.conf -D nl80211,wext; sleep 5

# Android runs DHCP for the group automatically; fall back to a static IP.
dhclient "$IFACE" 2>/dev/null || dhcpcd "$IFACE" 2>/dev/null || ip addr add 192.168.49.100/24 dev "$IFACE"

echo "Checking phone SOCKS5 proxy at $GATEWAY:$PROXY_PORT ..."
if command -v nc >/dev/null 2>&1 && nc -z -w 5 "$GATEWAY" "$PROXY_PORT" 2>/dev/null; then
    echo "OK - phone SOCKS5 proxy is reachable."
else
    echo "WARNING: could not confirm the proxy. Make sure the phone app shows ACTIVE."
fi

cat << EOF

================= TetherDirect (Linux) =================
Joined: $SSID
Proxy : socks5://$GATEWAY:$PROXY_PORT

The phone shares data as a SOCKS5 proxy (no NAT on the phone),
so use the proxy directly:

  # For proxy-aware apps / most CLI tools:
  export ALL_PROXY=socks5://$GATEWAY:$PROXY_PORT

  # Firefox/Chrome: set SOCKS5 host $GATEWAY port $PROXY_PORT

  # For a full-system tunnel, run tun2socks (Linux build) pointed at
  # socks5://$GATEWAY:$PROXY_PORT, same as the Windows app does.

To disconnect:  sudo bash disconnect.sh $IFACE
========================================================
EOF
