#!/bin/bash
# Copies the control node's SSH public key to all DB nodes.
# Run this from the control container after docker-compose up.

set -e

for node in n1 n2 n3 n4 n5; do
  echo "Setting up SSH for $node..."
  sshpass -p root ssh-copy-id -o StrictHostKeyChecking=no root@$node 2>/dev/null
  echo "  $node OK"
done

echo "SSH setup complete. Testing connections..."
for node in n1 n2 n3 n4 n5; do
  ssh root@$node hostname
done
echo "All nodes reachable."
