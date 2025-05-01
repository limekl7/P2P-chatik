import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.Set;

public class PeerChatView {
    private PeerChat peerChat;
    private JTextPane chatTextPane;
    private JTextPane systemTextPane;
    private JTextField messageField;
    private JList<String> userList;

    public PeerChatView(PeerChat peerChat) {
        this.peerChat = peerChat;
    }

    public void init() {
        JFrame frame = new JFrame("PeerChat - " + peerChat.getName() + "@" + peerChat.getIp() + ":" + peerChat.getTcpPort());
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 400);

        JTabbedPane tabbedPane = new JTabbedPane();

        JPanel chatPanel = new JPanel(new BorderLayout());
        chatTextPane = new JTextPane();
        chatTextPane.setEditable(false);
        chatPanel.add(new JScrollPane(chatTextPane), BorderLayout.CENTER);

        JPanel inputPanel = new JPanel(new BorderLayout());
        messageField = new JTextField();
        messageField.addActionListener(e -> sendMessage());
        JButton sendButton = new JButton("Отправить");
        sendButton.addActionListener(e -> sendMessage());
        inputPanel.add(messageField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        chatPanel.add(inputPanel, BorderLayout.SOUTH);

        JPanel systemPanel = new JPanel(new BorderLayout());
        systemTextPane = new JTextPane();
        systemTextPane.setEditable(false);
        systemPanel.add(new JScrollPane(systemTextPane), BorderLayout.CENTER);

        tabbedPane.addTab("Чат", chatPanel);
        tabbedPane.addTab("Система", systemPanel);

        userList = new JList<>();
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        updateUserList();

        frame.setLayout(new BorderLayout());
        frame.add(tabbedPane, BorderLayout.CENTER);
        frame.add(new JScrollPane(userList), BorderLayout.EAST);

        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                peerChat.stop();
            }
        });

        frame.setVisible(true);
    }

    private void sendMessage() {
        String text = messageField.getText().trim();
        if (!text.isEmpty()) {
            peerChat.sendTextMessage(text);
            messageField.setText("");
        }
    }

    public void appendOwnMessage(String message) {
        appendToTextPane(chatTextPane, message + "\n", Color.BLUE);
    }

    public void appendPeerMessage(String message) {
        appendToTextPane(chatTextPane, message + "\n", Color.GREEN);
    }

    public void appendSystemMessage(String message) {
        appendToTextPane(systemTextPane, message + "\n", Color.BLACK);
    }

    public void appendConnectionNotification(String message) {
        appendToTextPane(chatTextPane, message + "\n", Color.GRAY);
    }

    private void appendToTextPane(JTextPane textPane, String message, Color color) {
        StyledDocument doc = textPane.getStyledDocument();
        Style style = textPane.addStyle("ColorStyle", null);
        StyleConstants.setForeground(style, color);
        try {
            doc.insertString(doc.getLength(), message, style);
            textPane.setCaretPosition(doc.getLength());
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    public void refreshMessages() {
        chatTextPane.setText("");
        systemTextPane.setText("");
        String myPeerId = peerChat.getName() + "@" + peerChat.getIp() + ":" + peerChat.getTcpPort();
        for (String event : peerChat.getEventHistory()) {
            if (event.contains("SYSTEM")) {
                appendToTextPane(systemTextPane, event + "\n", Color.BLACK);
            } else if (event.contains(" подключился") || event.contains(" отключился")) {
                appendToTextPane(chatTextPane, event + "\n", Color.GRAY);
            } else if (event.contains(myPeerId)) {
                String timestamp = event.substring(0, 20);
                String messageContent = event.split(": ", 2)[1];
                String formatted = String.format("%s You (%s): %s", timestamp, peerChat.getIp(), messageContent);
                appendToTextPane(chatTextPane, formatted + "\n", Color.BLUE);
            } else if (event.contains("@")) {
                appendToTextPane(chatTextPane, event + "\n", Color.GREEN);
            } else {
                // Для случаев, когда формат неизвестен (например, старые логи)
                appendToTextPane(chatTextPane, event + "\n", Color.GREEN);
            }
        }
    }

    public void updateUserList() {
        Set<String> users = peerChat.getUsers();
        userList.setListData(users.toArray(new String[0]));
    }

    public String getName() {
        return peerChat.getName();
    }

    public String getIp() {
        return peerChat.getIp();
    }

    public int getTcpPort() {
        return peerChat.getTcpPort();
    }
}