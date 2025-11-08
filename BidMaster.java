
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CopyOnWriteArrayList;

/** Server GUI + logic combined in one file: BidMaster.java */
public class BidMaster extends JFrame {
    private JTextField itemField, startBidField, minIncField;
    private JButton startButton, newAuctionButton, finalButton;
    private JTextArea logArea;
    private JLabel currentBidLabel, highestBidderLabel, timerLabel;

    private BidMasterLogic logic; // backend reference

    public BidMaster() {
        setTitle("Auction Master - Server");
        setSize(720, 560);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // --- Top: item + starting bid + min increment + buttons
        JPanel top = new JPanel();
        top.add(new JLabel("Item:"));
        itemField = new JTextField(14);
        top.add(itemField);

        top.add(new JLabel("Start Bid:"));
        startBidField = new JTextField(6);
        top.add(startBidField);

        top.add(new JLabel("Min Step:"));
        minIncField = new JTextField(6);
        top.add(minIncField);

        startButton = new JButton("Start Auction");
        newAuctionButton = new JButton("New Auction");
        top.add(startButton);
        top.add(newAuctionButton);
        add(top, BorderLayout.NORTH);

        // --- Center: logs
        logArea = new JTextArea();
        logArea.setEditable(false);
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        // --- Bottom: status + final + timer
        JPanel bottom = new JPanel(new GridLayout(2, 2, 8, 8));
        currentBidLabel = new JLabel("Current Bid: $0.00");
        highestBidderLabel = new JLabel("Highest Bidder: None");
        timerLabel = new JLabel("Time left: -- s");
        finalButton = new JButton("Request Final Bid");
        finalButton.setEnabled(false);
        bottom.add(currentBidLabel);
        bottom.add(highestBidderLabel);
        bottom.add(timerLabel);
        bottom.add(finalButton);
        add(bottom, BorderLayout.SOUTH);

        // Init logic
        logic = new BidMasterLogic(this);
        logic.startServer();

        // Actions
        startButton.addActionListener(e -> {
            String item = itemField.getText().trim();
            if (item.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Enter an item name first.");
                return;
            }
            double startBid = parseOrDefault(startBidField.getText().trim(), 0.0);
            double minStep = parseOrDefault(minIncField.getText().trim(), 0.0);

            startButton.setEnabled(false);
            finalButton.setEnabled(true);
            logic.startAuction(item, startBid, minStep);
        });

        newAuctionButton.addActionListener(e -> {
            String newItem = JOptionPane.showInputDialog(this, "Enter new item:");
            if (newItem != null && !newItem.trim().isEmpty()) {
                double startBid = parseOrDefault(startBidField.getText().trim(), 0.0);
                double minStep = parseOrDefault(minIncField.getText().trim(), 0.0);
                logic.resetAuction(newItem.trim(), startBid, minStep);
                resetLabels();
            }
        });

        finalButton.addActionListener(e -> logic.requestFinalBid());

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { logic.closeServer(); }
        });

        setVisible(true);
    }

    private double parseOrDefault(String txt, double def) {
        if (txt.isEmpty()) return def;
        try { return Double.parseDouble(txt); } catch (NumberFormatException e) { return def; }
    }

    /** Append a timestamped line to the log area; auto-scrolls. */
    public void log(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + time + "] " + msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /** Update labels when a new top bid arrives. */
    public void updateCurrentBid(String bidder, double amount) {
        SwingUtilities.invokeLater(() -> {
            currentBidLabel.setText("Current Bid: $" + String.format("%.2f", amount));
            highestBidderLabel.setText("Highest Bidder: " + bidder);
        });
    }

    /** Update timer label from logic. */
    public void updateTimer(int seconds) {
        SwingUtilities.invokeLater(() -> {
            if (seconds <= 0) {
                timerLabel.setText("Time left: -- s");
            } else {
                timerLabel.setText("Time left: " + seconds + " s");
            }
        });
    }

    /** Reset labels when NEW_AUCTION is started. */
    private void resetLabels() {
        currentBidLabel.setText("Current Bid: $0.00");
        highestBidderLabel.setText("Highest Bidder: None");
        logArea.setText("");
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(BidMaster::new);
    }
}

/**
 * Package-private logic class used only by BidMaster.
 * Same behavior as before: starting bid, min increment, timer, final confirm.
 */
class BidMasterLogic {
    private static final int PORT = 5000;
    private static final int BID_TIME_SECONDS = 30; // time to bid or extend after each bid

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final java.util.concurrent.CopyOnWriteArrayList<ClientHandler> clients =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    // Auction state
    private String currentItem = "";
    private String lastBidder = "";
    private double lastBid = 0.0;
    private double startingBid = 0.0;
    private double minIncrement = 0.0;
    private boolean waitingForFinal = false;

    // Timer
    private javax.swing.Timer bidTimer;
    private int timeLeftSeconds = 0;

    private final BidMaster gui; // reference to server GUI for callbacks

    public BidMasterLogic(BidMaster gui) {
        this.gui = gui;
    }

    /** Starts the TCP server and begins accepting clients. */
    public void startServer() {
        acceptThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                gui.log("Server started on port " + PORT);
                while (!serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    ClientHandler handler = new ClientHandler(socket);
                    clients.add(handler);
                    handler.start();
                }
            } catch (IOException e) {
                gui.log("Server stopped.");
            }
        }, "accept-thread");
        acceptThread.start();
    }

    /** Starts a new auction for the given item, with starting bid and min increment. */
    public synchronized void startAuction(String item, double startingBid, double minIncrement) {
        this.currentItem = item;
        this.startingBid = startingBid;
        this.minIncrement = minIncrement;
        this.lastBidder = "";
        this.lastBid = 0.0; // no actual bids yet
        this.waitingForFinal = false;

        startBidTimer(); // start countdown

        // Tell clients: START|item|startingBid|minIncrement
        broadcast("START|" + currentItem + "|" + startingBid + "|" + minIncrement);
        gui.log("Auction started for item: " + currentItem +
                " (starting $" + startingBid + ", min step $" + minIncrement + ")");
        gui.updateCurrentBid("None", 0.0);
    }

    /** Resets current auction with a new item; clients remain connected. */
    public synchronized void resetAuction(String newItem, double startingBid, double minIncrement) {
        stopBidTimer();
        this.currentItem = newItem;
        this.startingBid = startingBid;
        this.minIncrement = minIncrement;
        this.lastBidder = "";
        this.lastBid = 0.0;
        this.waitingForFinal = false;

        startBidTimer();

        // Tell clients: NEW_AUCTION|item|startingBid|minIncrement
        broadcast("NEW_AUCTION|" + currentItem + "|" + startingBid + "|" + minIncrement);
        gui.log("New auction started for: " + currentItem +
                " (starting $" + startingBid + ", min step $" + minIncrement + ")");
        gui.updateCurrentBid("None", 0.0);
    }

    /** Requests final confirmation from the last bidder, if any. */
    public synchronized void requestFinalBid() {
        if (lastBidder == null || lastBidder.isEmpty()) {
            JOptionPane.showMessageDialog(null, "No bids yet!");
            return;
        }
        waitingForFinal = true;
        stopBidTimer(); // pause timer during final confirm
        broadcast("FINAL_REQUEST|" + lastBidder + "|" + lastBid);
        gui.log("Final bid requested from " + lastBidder);
    }

    /** Broadcast message to all connected clients. */
    private void broadcast(String msg) {
        for (ClientHandler c : clients) {
            c.send(msg);
        }
    }

    /** Cleanly close all sockets and stop the server. */
    public void closeServer() {
        stopBidTimer();
        try {
            for (ClientHandler c : clients) c.close();
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException ignored) {}
    }

    /** Start / restart the bid countdown timer. */
    private synchronized void startBidTimer() {
        stopBidTimer();
        timeLeftSeconds = BID_TIME_SECONDS;
        gui.updateTimer(timeLeftSeconds);
        bidTimer = new javax.swing.Timer(1000, e -> {
            synchronized (BidMasterLogic.this) {
                timeLeftSeconds--;
                if (timeLeftSeconds >= 0) {
                    gui.updateTimer(timeLeftSeconds);
                    broadcast("TIME|" + timeLeftSeconds);
                }
                if (timeLeftSeconds <= 0) {
                    stopBidTimer();
                    // Time's up – end auction automatically
                    if (lastBidder != null && !lastBidder.isEmpty()) {
                        gui.log("Time up. Auto-ending auction. Winner: " + lastBidder);
                        broadcast("END|" + lastBidder + "|" + lastBid);
                        JOptionPane.showMessageDialog(null,
                                "Time up! Winner: " + lastBidder + " ($" + lastBid + ")");
                    } else {
                        gui.log("Time up. No winning bids.");
                        broadcast("INFO|Time up. No winning bids.");
                    }
                    waitingForFinal = false;
                }
            }
        });
        bidTimer.start();
    }

    /** Stop timer if running. */
    private synchronized void stopBidTimer() {
        if (bidTimer != null) {
            bidTimer.stop();
            bidTimer = null;
        }
        gui.updateTimer(0);
    }

    /** Handles one client connection. */
    private class ClientHandler extends Thread {
        private final Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private String name = "";

        ClientHandler(Socket socket) {
            super("client-" + socket.getRemoteSocketAddress());
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);

                String line;
                while ((line = in.readLine()) != null) {
                    handle(line);
                }
            } catch (IOException ex) {
                gui.log("Client disconnected: " + name);
            } finally {
                clients.remove(this);
                close();
            }
        }

        private void handle(String msg) {
            try {
                if (msg.startsWith("JOIN|")) {
                    String[] parts = msg.split("\\|", 2);
                    if (parts.length < 2) return;
                    name = parts[1].trim();
                    gui.log(name + " joined the auction.");
                    broadcast("INFO|" + name + " joined the auction.");
                } else if (msg.startsWith("BID|")) {
                    String[] parts = msg.split("\\|");
                    if (parts.length < 3) { send("INFO|Malformed BID"); return; }

                    String bidder = parts[1].trim();
                    double amount = Double.parseDouble(parts[2].trim()); // may throw NFE

                    synchronized (BidMasterLogic.this) {
                        // Enforce starting bid
                        if (amount < startingBid) {
                            send("INFO|Bid must be at least starting bid $" + startingBid);
                            return;
                        }
                        // Enforce min increment after at least one real bid
                        if (lastBid > 0 && minIncrement > 0 && amount < lastBid + minIncrement) {
                            double need = lastBid + minIncrement;
                            send("INFO|Bid must be at least $" + need +
                                    " (min increment $" + minIncrement + ")");
                            return;
                        }

                        // Accept bid
                        lastBid = amount;
                        lastBidder = bidder;
                        waitingForFinal = false; // any new bid cancels prior final window
                        startBidTimer(); // restart countdown
                        gui.updateCurrentBid(lastBidder, lastBid);
                        broadcast("BID|" + bidder + "|" + amount);
                        gui.log("New highest bid from " + bidder + ": $" + amount);
                    }
                } else if (msg.startsWith("FINAL_CONFIRM|")) {
                    String[] parts = msg.split("\\|");
                    if (parts.length < 2) return;
                    String confirmer = parts[1].trim();
                    synchronized (BidMasterLogic.this) {
                        if (waitingForFinal && confirmer.equals(lastBidder)) {
                            gui.log("Final confirmation received from " + confirmer);
                            broadcast("END|" + confirmer + "|" + lastBid);
                            JOptionPane.showMessageDialog(null,
                                    "Auction ended. Winner: " + confirmer + " ($" + lastBid + ")");
                            waitingForFinal = false;
                            stopBidTimer();
                        } else {
                            send("INFO|Only last bidder can confirm the final bid.");
                        }
                    }
                }
            } catch (NumberFormatException nfe) {
                send("INFO|Invalid number in message.");
            } catch (Exception ex) {
                gui.log("Malformed message ignored: " + msg);
            }
        }

        void send(String msg) { if (out != null) out.println(msg); }

        void close() {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            if (out != null) out.close();
            try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
        }
    }
}
