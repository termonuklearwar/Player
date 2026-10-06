import socket
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(('0.0.0.0', 5005))
print("Слушаю порт 5005...")
while True:
    data, addr = sock.recvfrom(4096)
    print(f"{addr}: {data.decode('utf-8', errors='replace')}")