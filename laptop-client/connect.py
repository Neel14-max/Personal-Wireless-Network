#!/usr/bin/env python3
"""
TetherDirect - Linux Client

Joins the phone's Wi-Fi Direct group and verifies the phone's SOCKS5 proxy is
reachable. The phone shares mobile data as a user-space SOCKS5 proxy (no root
and no NAT on the phone), so once connected you point your apps at
socks5://192.168.49.1:8888.

Requirements:
  - Linux (tested on Ubuntu 22.04+, Fedora 38+)
  - wpa_supplicant
  - dhclient or dhcpcd
  - python3 (no external deps)

Usage:
  sudo python3 connect.py                       # Auto-detect and connect
  sudo python3 connect.py --ssid DIRECT-xx --pass abc123
  sudo python3 connect.py --disconnect          # Disconnect and cleanup
"""

import subprocess
import sys
import re
import time
import argparse
import socket
import os


class TetherDirectClient:
    """Manages joining the phone's Wi-Fi Direct group and reaching its SOCKS5 proxy."""

    P2P_INTERFACE = "wlan0"       # Your laptop's Wi-Fi interface (change if needed)
    GATEWAY_IP = "192.168.49.1"   # The phone (Wi-Fi Direct Group Owner)
    PROXY_PORT = 8888             # The phone's SOCKS5 proxy
    EXPECTED_IP = "192.168.49.100"

    def __init__(self, ssid: str = None, password: str = None):
        self.ssid = ssid
        self.password = password

    def run(self, cmd: str, check: bool = True, capture: bool = True) -> str:
        """Run a shell command and return output."""
        print(f"  $ {cmd}")
        result = subprocess.run(
            cmd, shell=True, capture_output=capture, text=True, check=check
        )
        if capture:
            output = (result.stdout + result.stderr).strip()
            if output:
                print(f"    {output}")
            return output
        return ""

    def check_root(self):
        """Verify running as root."""
        if os.geteuid() != 0:
            print("ERROR: This script must be run with sudo.")
            print("  Use: sudo python3 connect.py")
            sys.exit(1)

    def detect_wifi_interface(self) -> str:
        """Detect the Wi-Fi interface name."""
        output = self.run("ip link show | grep -E 'wl[an]' | awk -F: '{print $2}' | tr -d ' '")
        interfaces = [i for i in output.split('\n') if i.strip()]
        if not interfaces:
            print("ERROR: No Wi-Fi interface found.")
            sys.exit(1)
        print(f"  Found Wi-Fi interfaces: {', '.join(interfaces)}")
        self.P2P_INTERFACE = interfaces[0]
        return self.P2P_INTERFACE

    def scan_for_p2p_groups(self) -> list:
        """Scan for available Wi-Fi Direct groups (SSIDs starting with DIRECT-)."""
        print("\n[2] Scanning for Wi-Fi Direct groups...")
        self.run(f"ip link set {self.P2P_INTERFACE} up")
        time.sleep(2)

        output = self.run(f"iwlist {self.P2P_INTERFACE} scan | grep -E 'ESSID|Address'", check=False)

        groups = []
        current = {}
        for line in output.split('\n'):
            essid_match = re.search(r'ESSID:"(.+?)"', line)
            addr_match = re.search(r'Address: ([0-9A-F:]+)', line)
            if essid_match:
                current['ssid'] = essid_match.group(1)
            if addr_match:
                current['bssid'] = addr_match.group(1)
            if current.get('ssid') and current.get('bssid'):
                groups.append(current.copy())
                current = {}

        return [g for g in groups if g.get('ssid', '').startswith('DIRECT-')]

    def connect_wpa_supplicant(self, ssid: str, password: str):
        """Connect using wpa_supplicant."""
        print(f"\n[3] Connecting to '{ssid}'...")

        self.run(f"wpa_cli -i {self.P2P_INTERFACE} terminate", check=False)
        time.sleep(1)

        config = f"""ctrl_interface=/var/run/wpa_supplicant
ctrl_interface_group=0
update_config=1

network={{
    ssid="{ssid}"
    psk="{password}"
    key_mgmt=WPA-PSK
    proto=RSN
    pairwise=CCMP
    group=CCMP
    scan_ssid=1
}}"""

        config_path = "/tmp/tetherdirect_wpa.conf"
        with open(config_path, 'w') as f:
            f.write(config)

        self.run(f"wpa_supplicant -B -i {self.P2P_INTERFACE} -c {config_path} -D nl80211,wext")
        time.sleep(3)

        status = self.run(f"wpa_cli -i {self.P2P_INTERFACE} status", check=False)
        if "wpa_state=COMPLETED" in status:
            print(f"  Connected to '{ssid}'!")
            return True
        print(f"  Connection status: {status}")
        return False

    def request_dhcp(self):
        """Request an IP via DHCP (Android runs DHCP for the group automatically)."""
        print(f"\n[4] Requesting IP via DHCP...")
        try:
            self.run(f"dhclient -v {self.P2P_INTERFACE}", check=False)
        except Exception:
            pass
        time.sleep(2)

        output = self.run(f"ip addr show {self.P2P_INTERFACE} | grep 'inet ' | awk '{{print $2}}'")
        if output:
            ip = output.split('/')[0].strip()
            print(f"  Got IP: {ip}")
            return ip
        return None

    def verify_proxy(self) -> bool:
        """Check the phone's SOCKS5 proxy is reachable."""
        print(f"\n[5] Checking phone SOCKS5 proxy at {self.GATEWAY_IP}:{self.PROXY_PORT} ...")
        try:
            with socket.create_connection((self.GATEWAY_IP, self.PROXY_PORT), timeout=5):
                print("  OK - phone SOCKS5 proxy is reachable.")
                return True
        except OSError as e:
            print(f"  Could not reach the proxy: {e}")
            print("  Make sure the phone app shows ACTIVE / P2P READY.")
            return False

    def disconnect(self):
        """Disconnect and restore network."""
        print("\n[*] Disconnecting TetherDirect...")
        self.run(f"wpa_cli -i {self.P2P_INTERFACE} terminate", check=False)
        self.run(f"ip addr flush dev {self.P2P_INTERFACE}", check=False)
        self.run(f"ip link set {self.P2P_INTERFACE} down", check=False)
        # Hand the interface back to the normal network manager if present.
        self.run("systemctl start NetworkManager", check=False)
        print("  Disconnected. Your original network should be restored.")

    def connect(self):
        """Full connection flow."""
        print("=" * 52)
        print("  TetherDirect - Linux Client")
        print("=" * 52)

        self.check_root()

        print("\n[1] Detecting Wi-Fi interface...")
        self.detect_wifi_interface()

        if not self.ssid:
            groups = self.scan_for_p2p_groups()
            if not groups:
                print("\n  No Wi-Fi Direct groups found!")
                print("  Make sure:")
                print("    1. Phone app is running and sharing is ACTIVE")
                print("    2. Phone's Wi-Fi is ON")
                print("    3. You're within Wi-Fi range")
                print("\n  To specify manually: sudo python3 connect.py --ssid DIRECT-xx --pass yourpass")
                sys.exit(1)

            print(f"  Found {len(groups)} group(s):")
            for i, g in enumerate(groups):
                print(f"    [{i}] {g['ssid']} (BSSID: {g.get('bssid', '?')})")

            if len(groups) == 1:
                self.ssid = groups[0]['ssid']
                print(f"  Auto-selecting: {self.ssid}")
            else:
                idx = int(input("  Select group number: "))
                self.ssid = groups[idx]['ssid']

        if not self.password:
            self.password = input(f"  Enter password for '{self.ssid}': ").strip()
            if not self.password:
                print("  Password is required.")
                sys.exit(1)

        if not self.connect_wpa_supplicant(self.ssid, self.password):
            print("\n  Failed to connect. Check the password and try again.")
            sys.exit(1)

        ip = self.request_dhcp()
        if not ip:
            print("  WARNING: DHCP failed. Trying static IP...")
            self.run(f"ip addr add {self.EXPECTED_IP}/24 dev {self.P2P_INTERFACE}")
            ip = self.EXPECTED_IP

        ok = self.verify_proxy()

        print("\n" + "=" * 52)
        if ok:
            print("  JOINED. The phone shares data as a SOCKS5 proxy.")
        else:
            print("  Joined the group, but the proxy wasn't reachable yet.")
        print(f"  Your IP : {ip}")
        print(f"  Proxy   : socks5://{self.GATEWAY_IP}:{self.PROXY_PORT}")
        print("=" * 52)
        print("\n  Use the proxy directly (the phone does NOT NAT):")
        print(f"    export ALL_PROXY=socks5://{self.GATEWAY_IP}:{self.PROXY_PORT}")
        print(f"    # or set Firefox/Chrome SOCKS5 host {self.GATEWAY_IP} port {self.PROXY_PORT}")
        print(f"    # or run tun2socks (Linux) -> socks5://{self.GATEWAY_IP}:{self.PROXY_PORT} for a full tunnel")
        print("\n  To disconnect: sudo python3 connect.py --disconnect")


def main():
    parser = argparse.ArgumentParser(description="TetherDirect Linux Client")
    parser.add_argument("--ssid", help="Wi-Fi Direct SSID (e.g. DIRECT-xx)")
    parser.add_argument("--pass", dest="password", help="Wi-Fi Direct password")
    parser.add_argument("--interface", help="Wi-Fi interface (default: auto-detect)")
    parser.add_argument("--disconnect", action="store_true", help="Disconnect and clean up")

    args = parser.parse_args()

    client = TetherDirectClient(ssid=args.ssid, password=args.password)
    if args.interface:
        client.P2P_INTERFACE = args.interface

    if args.disconnect:
        client.disconnect()
    else:
        client.connect()


if __name__ == "__main__":
    main()
