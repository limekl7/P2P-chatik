import javax.swing.*;
import java.io.IOException;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PeerChat {
    private String name;
    private String ip;
    private int tcpPort;
    private static final int UDP_PORT = 12345;
    private Map<String, Connection> connections = new ConcurrentHashMap<>();
    private List<String> eventHistory = Collections.synchronizedList(new ArrayList<>());
    private Map<String, Long> recentMessages = new ConcurrentHashMap<>();
    private PeerChatView view;
    private volatile boolean running = true;
    private ScheduledExecutorService broadcastScheduler;
    private ScheduledExecutorService cleanupScheduler;
    private Set<String> knownPeers = Collections.synchronizedSet(new HashSet<>());

    public PeerChat(String name, String ip, int tcpPort) {
        this.name = name;
        this.ip = ip;
        this.tcpPort = tcpPort;
        this.view = new PeerChatView(this);
    }

    public void start() {
        SwingUtilities.invokeLater(() -> view.init());
        new Thread(this::listenForBroadcast).start();
        new Thread(this::listenForConnections).start();
        broadcastScheduler = Executors.newScheduledThreadPool(1);
        broadcastScheduler.scheduleAtFixedRate(this::sendBroadcast, 0, 10, TimeUnit.SECONDS);
        cleanupScheduler = Executors.newScheduledThreadPool(1);
        cleanupScheduler.scheduleAtFixedRate(this::cleanupRecentMessages, 1, 1, TimeUnit.MINUTES);
    }

    private void cleanupRecentMessages() {
        long currentTime = System.currentTimeMillis();
        long expirationTime = 5 * 60 * 1000;
        synchronized (recentMessages) {
            recentMessages.entrySet().removeIf(entry ->
                    (currentTime - entry.getValue()) > expirationTime);
        }
        addSystemEvent("Очищены устаревшие сообщения, текущий размер recentMessages: " + recentMessages.size());
    }

    private void sendBroadcast() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            String message = name + ":" + ip + ":" + tcpPort;
            DatagramPacket broadcastPacket = new DatagramPacket(
                    message.getBytes(),
                    message.length(),
                    InetAddress.getByName("255.255.255.255"),
                    UDP_PORT
            );
            socket.send(broadcastPacket);
            addSystemEvent("Отправлен широковещательный пакет на 255.255.255.255:" + UDP_PORT + ": " + message);
            String[] loopbackAddresses = {"127.0.0.1", "127.0.0.2"};
            for (String addr : loopbackAddresses) {
                if (!addr.equals(ip)) {
                    DatagramPacket directPacket = new DatagramPacket(
                            message.getBytes(),
                            message.length(),
                            InetAddress.getByName(addr),
                            UDP_PORT
                    );
                    socket.send(directPacket);
                    addSystemEvent("Отправлен прямой пакет на " + addr + ":" + UDP_PORT + ": " + message);
                }
            }
        } catch (IOException e) {
            addSystemEvent("Ошибка отправки широковещательного пакета: " + e.getMessage());
        }
    }

    private void listenForBroadcast() {
        try (DatagramSocket socket = new DatagramSocket(UDP_PORT, InetAddress.getByName(ip))) {
            byte[] buffer = new byte[1024];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String[] data = new String(packet.getData(), 0, packet.getLength()).split(":");
                String peerName = data[0];
                String peerIp = data[1];
                int peerTcpPort = Integer.parseInt(data[2]);
                String peerId = peerName + "@" + peerIp + ":" + peerTcpPort;
                addSystemEvent("Получен пакет от " + peerId);
                if (!peerName.equals(name) && peerTcpPort != tcpPort && !peerIp.equals(ip)) {
                    knownPeers.add(peerId);
                    connectToPeer(peerName, peerIp, peerTcpPort);
                }
            }
        } catch (IOException e) {
            addSystemEvent("Ошибка приема широковещательного пакета: " + e.getMessage());
        }
    }

    private void listenForConnections() {
        try (ServerSocket serverSocket = new ServerSocket(tcpPort, 50, InetAddress.getByName(ip))) {
            addSystemEvent("TCP-сервер запущен на " + ip + ":" + tcpPort);
            while (running) {
                Socket socket = serverSocket.accept();
                Connection connection = new Connection(socket);
                addSystemEvent("Принято входящее соединение от " + socket.getRemoteSocketAddress());
                new Thread(() -> handleConnection(connection)).start();
            }
        } catch (IOException e) {
            addSystemEvent("Ошибка TCP-сервера: " + e.getMessage());
        }
    }

    private void connectToPeer(String peerName, String peerIp, int peerTcpPort) {
        String peerId = peerName + "@" + peerIp + ":" + peerTcpPort;

        synchronized (connections) {
            if (connections.containsKey(peerId)) {
                addSystemEvent("Узел " + peerId + " уже подключен, пропускаем");
                return;
            }
        }

        if (peerIp.equals(ip) && peerTcpPort == tcpPort) {
            addSystemEvent("Пропущено подключение к самому себе: " + peerId);
            return;
        }

        try {
            Thread.sleep(100);
            synchronized (connections) {
                if (connections.containsKey(peerId)) {
                    addSystemEvent("Узел " + peerId + " уже подключен после задержки, пропускаем");
                    return;
                }
                Socket socket = new Socket(peerIp, peerTcpPort);
                Connection connection = new Connection(socket);
                connections.put(peerId, connection);
                connection.send(new Message(MessageType.USER_NAME, name + ":" + ip + ":" + tcpPort));
                addSystemEvent("Установлено соединение с " + peerId);
                addSystemEvent("Текущие соединения: " + connections.keySet());
                new Thread(() -> handleConnection(connection)).start();
                connection.send(new Message(MessageType.HISTORY_REQUEST));
            }
        } catch (IOException e) {
            addSystemEvent("Ошибка подключения к " + peerId + ": " + e.getMessage());
        } catch (InterruptedException e) {
            addSystemEvent("Ошибка задержки: " + e.getMessage());
        }
    }

    private void handleConnection(Connection connection) {
        SocketAddress remoteAddr = connection.getRemoteSocketAddress();
        String tempPeerId = remoteAddr.toString();
        String finalPeerId = tempPeerId;

        try {
            while (running) {
                Message message = connection.receive();
                if (message == null) continue;
                switch (message.getType()) {
                    case USER_NAME:
                        String[] userData = message.getData().split(":");
                        String peerName = userData[0];
                        String peerIp = userData[1];
                        int peerPort = Integer.parseInt(userData[2]);
                        finalPeerId = peerName + "@" + peerIp + ":" + peerPort;

                        synchronized (connections) {
                            Iterator<Map.Entry<String, Connection>> iterator = connections.entrySet().iterator();
                            while (iterator.hasNext()) {
                                Map.Entry<String, Connection> entry = iterator.next();
                                String existingPeerId = entry.getKey();
                                Connection existingConn = entry.getValue();
                                if (existingPeerId.equals(finalPeerId) && existingConn != connection) {
                                    addSystemEvent("Обнаружен дубликат соединения с " + finalPeerId + ", закрываем новое");
                                    connection.close();
                                    return;
                                }
                            }
                            connections.entrySet().removeIf(entry -> entry.getValue() == connection);
                            connections.put(finalPeerId, connection);
                        }
                        addSystemEvent("Подключен узел: " + finalPeerId);
                        addConnectionNotification("[" + finalPeerId + "] подключился");
                        view.updateUserList();
                        connection.send(new Message(MessageType.HISTORY_REQUEST));
                        break;
                    case TEXT:
                        if (finalPeerId.equals(tempPeerId)) {
                            addSystemEvent("Сообщение от неизвестного узла, ждём USER_NAME: " + message.getData());
                            continue;
                        }
                        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                        String messageHash = finalPeerId + ":" + message.getData() + ":" + timestamp;
                        addSystemEvent("Получено сообщение от " + finalPeerId + ": " + message.getData());
                        synchronized (recentMessages) {
                            if (recentMessages.containsKey(messageHash)) {
                                addSystemEvent("Пропущено дублирующее сообщение от " + finalPeerId + ": " + message.getData());
                                break;
                            }
                            recentMessages.put(messageHash, System.currentTimeMillis());
                        }
                        addPeerMessage(finalPeerId, message.getData());
                        connection.send(new Message(MessageType.ACK, messageHash));
                        broadcastMessage(message, connection);
                        break;
                    case ACK:
                        addSystemEvent("Получено подтверждение от " + finalPeerId + " для сообщения: " + message.getData());
                        break;
                    case HISTORY_REQUEST:
                        String history = String.join("\n", eventHistory.stream()
                                .filter(event -> event.contains("@") || event.contains(" подключился") || event.contains(" отключился"))
                                .toList());
                        connection.send(new Message(MessageType.HISTORY_RESPONSE, history));
                        addSystemEvent("Отправлена история узлу " + finalPeerId + ":\n" + history);
                        break;
                    case HISTORY_RESPONSE:
                        String[] events = message.getData().split("\n");
                        synchronized (eventHistory) {
                            for (String event : events) {
                                if (event.trim().isEmpty()) continue;
                                if (!eventHistory.contains(event)) {
                                    eventHistory.add(event);
                                }
                            }
                        }
                        view.refreshMessages();
                        view.updateUserList();
                        addSystemEvent("Получена история от " + finalPeerId + ":\n" + message.getData());
                        break;
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            synchronized (connections) {
                connections.remove(finalPeerId);
            }
            addSystemEvent("Узел отключен: " + finalPeerId);
            addConnectionNotification("[" + finalPeerId + "] отключился");
            view.updateUserList();
        } finally {
            try {
                connection.close();
            } catch (IOException ignored) {}
            synchronized (connections) {
                connections.remove(finalPeerId);
            }
            view.updateUserList();
        }
    }

    public void sendTextMessage(String text) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String peerId = name + "@" + ip + ":" + tcpPort;
        String event = String.format("[%s] %s: %s", timestamp, peerId, text);
        synchronized (eventHistory) {
            if (!eventHistory.contains(event)) {
                eventHistory.add(event);
            }
        }
        view.appendOwnMessage(String.format("[%s] You (%s): %s", timestamp, ip, text));
        Message message = new Message(MessageType.TEXT, text);
        broadcastMessage(message, null);
        addSystemEvent("Отправлено сообщение: " + text);
    }

    private void broadcastMessage(Message message, Connection sender) {
        for (Map.Entry<String, Connection> entry : connections.entrySet()) {
            Connection conn = entry.getValue();
            if (conn != sender) {
                try {
                    conn.send(message);
                    addSystemEvent("Сообщение отправлено узлу " + entry.getKey() + ": " + message.getData());
                } catch (IOException e) {
                    addSystemEvent("Ошибка отправки сообщения узлу " + entry.getKey() + ": " + e.getMessage());
                }
            }
        }
    }

    private void addSystemEvent(String event) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String formatted = String.format("[%s] SYSTEM: %s", timestamp, event);
        synchronized (eventHistory) {
            if (!eventHistory.contains(formatted)) {
                eventHistory.add(formatted);
            }
        }
        view.appendSystemMessage(formatted);
    }

    private void addPeerMessage(String peerId, String message) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String formatted = String.format("[%s] %s: %s", timestamp, peerId, message);
        synchronized (eventHistory) {
            if (!eventHistory.contains(formatted)) {
                eventHistory.add(formatted);
            }
        }
        view.appendPeerMessage(formatted);
        view.refreshMessages(); // Обновляем GUI после каждого нового сообщения
    }

    private void addConnectionNotification(String message) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String formatted = String.format("[%s] %s", timestamp, message);
        synchronized (eventHistory) {
            if (!eventHistory.contains(formatted)) {
                eventHistory.add(formatted);
            }
        }
        view.appendConnectionNotification(formatted);
        view.refreshMessages();
    }

    public List<String> getEventHistory() {
        return eventHistory;
    }

    public Set<String> getUsers() {
        return connections.keySet();
    }

    public void stop() {
        running = false;
        broadcastScheduler.shutdown();
        cleanupScheduler.shutdown();
        for (Connection conn : connections.values()) {
            try {
                conn.close();
            } catch (IOException ignored) {}
        }
        connections.clear();
        view.updateUserList();
    }

    public String getName() {
        return name;
    }

    public String getIp() {
        return ip;
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public static void main(String[] args) {
        String name = JOptionPane.showInputDialog("Введите ваше имя:");
        String ip = JOptionPane.showInputDialog("Введите ваш IP (например, 127.0.0.1):");
        String tcpPortStr = JOptionPane.showInputDialog("Введите TCP-порт (например, 5000):");
        int tcpPort = Integer.parseInt(tcpPortStr);
        PeerChat peer = new PeerChat(name, ip, tcpPort);
        peer.start();
    }
}