#!/bin/bash
echo "[*] Flushing hidden NAT tables..."
iptables -t nat -F
iptables -t nat -X
iptables -F
iptables -X
iptables -P INPUT ACCEPT
iptables -P OUTPUT ACCEPT
iptables -P FORWARD ACCEPT

echo "[*] Resetting DNS and removing locks..."
chattr -i /etc/resolv.conf 2>/dev/null
echo -e "nameserver 1.1.1.1\nnameserver 8.8.8.8" > /etc/resolv.conf

echo "[*] Restarting Network Services..."
systemctl restart NetworkManager

echo "[+] Internet settings restored! Try loading a website."
