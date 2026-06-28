#!/data/data/com.termux/files/usr/bin/bash

# Torchain - Termux Gateway Server Setup Script
# This script configures your Android device (running Termux) as a Tor gateway.
# Other devices on the same Wi-Fi network can route their traffic through this device.

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0;0m'

echo -e "${BLUE}==================================================${NC}"
echo -e "${GREEN}    Torchain — Termux Tor Gateway Installer       ${NC}"
echo -e "${BLUE}==================================================${NC}"

# 1. Install required packages
echo -e "\n[*] Updating packages and installing Tor & Privoxy..."
pkg update -y
pkg install -y tor privoxy iproute2

# 2. Determine local IP address
echo -e "\n[*] Detecting network interfaces..."
# Extract the active local IP address (excluding loopback)
LOCAL_IP=$(ip -o -4 addr show | awk '{print $4}' | cut -d/ -f1 | grep -v '127.0.0.1' | head -n 1)

if [ -z "$LOCAL_IP" ]; then
    echo -e "${RED}[!] Warning: Could not auto-detect local IP address. Are you connected to Wi-Fi?${NC}"
    LOCAL_IP="0.0.0.0"
else
    echo -e "${GREEN}[+] Detected Local IP: $LOCAL_IP${NC}"
fi

# Create config directories
CONF_DIR="$HOME/.config/torchain-gateway"
mkdir -p "$CONF_DIR"

# 3. Write torrc configuration
TORRC_PATH="$CONF_DIR/torrc"
echo -e "[*] Writing Tor configuration to $TORRC_PATH..."
cat << EOF > "$TORRC_PATH"
# Torchain Termux Gateway torrc
DataDirectory $CONF_DIR/tor_data
AvoidDiskWrites 1

# Listen on all interfaces so local network devices can connect
SocksPort 0.0.0.0:9050
DNSPort 0.0.0.0:5400

# Security: Only allow local network subnets to connect
SocksPolicy accept 192.168.0.0/16
SocksPolicy accept 10.0.0.0/8
SocksPolicy accept 172.16.0.0/12
SocksPolicy reject *
EOF

# 4. Write Privoxy configuration (converts SOCKS5 to HTTP proxy for devices that don't support SOCKS5)
PRIVOXY_CONF_PATH="$CONF_DIR/privoxy.conf"
echo -e "[*] Writing Privoxy configuration to $PRIVOXY_CONF_PATH..."
cat << EOF > "$PRIVOXY_CONF_PATH"
# Torchain Termux Gateway Privoxy Config
confdir /data/data/com.termux/files/usr/etc/privoxy
logdir $CONF_DIR
logfile privoxy.log

# Listen on all interfaces on port 8118
listen-address  0.0.0.0:8118

# Forward all traffic to the local Tor SOCKS5 proxy
forward-socks5t / 127.0.0.1:9050 .

# Toggle filters
toggle  1
enable-remote-toggle  0
enable-remote-http-toggle  0
enable-edit-actions 0
buffer-limit 4096
keep-alive-timeout 5
socket-timeout 300
EOF

# 5. Create start script
START_SCRIPT="$HOME/start-gateway.sh"
echo -e "[*] Creating start script at $START_SCRIPT..."
cat << 'EOF' > "$START_SCRIPT"
#!/data/data/com.termux/files/usr/bin/bash
CONF_DIR="$HOME/.config/torchain-gateway"
LOCAL_IP=$(ip -o -4 addr show | awk '{print $4}' | cut -d/ -f1 | grep -v '127.0.0.1' | head -n 1)

echo "Starting Torchain Gateway..."
# Kill any existing instances
killall tor 2>/dev/null || true
killall privoxy 2>/dev/null || true

# Start Tor
tor -f "$CONF_DIR/torrc" --RunAsDaemon 1
# Start Privoxy
privoxy "$CONF_DIR/privoxy.conf"

echo "=================================================="
echo " Torchain Gateway is now RUNNING!"
echo "=================================================="
echo " Your Gateway IP: $LOCAL_IP"
echo "--------------------------------------------------"
echo " Configure other devices on your Wi-Fi network to:"
echo "   - HTTP Proxy:   $LOCAL_IP : 8118"
echo "   - SOCKS5 Proxy: $LOCAL_IP : 9050"
echo "   - DNS Server:   $LOCAL_IP : 5400"
echo "--------------------------------------------------"
echo " To stop the gateway, run: killall tor privoxy"
echo "=================================================="
EOF
chmod +x "$START_SCRIPT"

echo -e "${GREEN}\n[+] Setup Complete!${NC}"
echo -e "To start your Tor gateway server, run: ${BLUE}~/start-gateway.sh${NC}"
echo -e "${BLUE}==================================================${NC}"
