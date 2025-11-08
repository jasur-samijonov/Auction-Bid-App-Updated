
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Client GUI + logic combined in one file: BidMaker.java */
public class BidMaker extends JFrame {
    private JTextField nameField, bidField;
    private JButton joinButton, bidButton, confirmButton;
    private JTextArea logArea;
    private JLabel itemLabel, highestBidLabel, highestBidderLabel, timerLabel, startInfoLabel;

    private BidMakerLogic logic; // backend

    public BidMaker() {
        setTitle("Auction Bidder - Client");
        setSize(620, 540);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // --- Top: name + join
        JPanel top = new JPanel();
        top.add(new JLabel("Your Name:"));
        nameField = new JTextField(12);
        top.add(nameField);
        joinButton = new JButton("Join Auction");
        top.add(joinButton);
        add(top, BorderLayout.NORTH);

        // --- Center: logs
        logArea = new JTextArea();
        logArea.setEditable(false);
        add(new JScrollPane(logArea), BorderLayout.CENTER);

        // --- Bottom: status + bid controls
        JPanel bottom = new JPanel(new GridLayout(5, 2, 8, 8));
        itemLabel = new JLabel("Item: (waiting)");
        startInfoLabel = new JLabel("Start: -, Min step: -");
        highestBidLabel = new JLabel("Highest Bid: $0.00");
        highestBidderLabel = new JLabel("Highest Bidder: None");
        timerLabel = new JLabel("Time left: -- s");
        bidField = new JTextField();
        bidButton = new JButton("Place Bid");
        confirmButton = new JButton("Confirm Final Bid");
        bidButton.setEnabled(false);          // disabled before join/auction
        confirmButton.setEnabled(false);      // enabled only on final request

        bottom.add(itemLabel);
        bottom.add(startInfoLabel);
        bottom.add(highestBidLabel);
        bottom.add(highestBidderLabel);
        bottom.add(timerLabel);
        bottom.add(new JLabel("")); // spacer
        bottom.add(new JLabel("Your Bid:"));
        bottom.add(bidField);
        bottom.add(bidButton);
        bottom.add(confirmButton);
        add(bottom, BorderLayout.SOUTH);

        // Init logic
        logic = new BidMakerLogic(this);

        // Actions
        joinButton.addActionListener(e -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Enter your name before joining.");
                return;
            }
            logic.join(name);
            joinButton.setEnabled(false); // disable after joining
            log("Connected as " + name);
        });

        bidButton.addActionListener(e -> {
            logic.placeBid(bidField.getText());
            bidField.setText("");
        });

        confirmButton.addActionListener(e -> logic.confirmFinal());

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { logic.close(); }
        });

        setVisible(true);
    }

    // --- Callbacks from logic ---

    public void afterJoinSuccess() {
        bidButton.setEnabled(true);
    }

    public void enableConfirm(boolean enable) { confirmButton.setEnabled(enable); }

    public void onStart(String item, double startingBid, double minIncrement) {
        itemLabel.setText("Item: " + item);
        startInfoLabel.setText("Start: $" + String.format("%.2f", startingBid) +
                ", Min step: $" + String.format("%.2f", minIncrement));
        highestBidLabel.setText("Highest Bid: $0.00");
        highestBidderLabel.setText("Highest Bidder: None");
        bidButton.setEnabled(true);
        log("Auction started for " + item);
        bidField.requestFocusInWindow();
    }

    public void onBidUpdate(String bidder, double amount) {
        highestBidLabel.setText("Highest Bid: $" + String.format("%.2f", amount));
        highestBidderLabel.setText("Highest Bidder: " + bidder);
        log(bidder + " bid $" + String.format("%.2f", amount));
    }

    public void onFinalRequest(String lastBidder, double amount) {
        log("Final confirmation requested from " + lastBidder +
                " for $" + String.format("%.2f", amount));
    }

    public void onNewAuction(String item, double startingBid, double minIncrement) {
        itemLabel.setText("Item: " + item);
        startInfoLabel.setText("Start: $" + String.format("%.2f", startingBid) +
                ", Min step: $" + String.format("%.2f", minIncrement));
        highestBidLabel.setText("Highest Bid: $0.00");
        highestBidderLabel.setText("Highest Bidder: None");
        confirmButton.setEnabled(false);
        bidButton.setEnabled(true);
        log("New auction started for " + item);
        bidField.requestFocusInWindow();
    }

    public void onEnd(String winner, double amount) {
        confirmButton.setEnabled(false);
        bidButton.setEnabled(false);
        log("Auction ended. Winner: " + winner +
                " ($" + String.format("%.2f", amount) + ")");
    }

    public void onTimer(int seconds) {
        if (seconds <= 0) {
            timerLabel.setText("Time left: -- s");
        } else {
            timerLabel.setText("Time left: " + seconds + " s");
        }
    }

    public void onDisconnected() {
        confirmButton.setEnabled(false);
        bidButton.setEnabled(false);
        joinButton.setEnabled(true);
        log("Disconnected from server.");
    }

    /** Append a timestamped line to the log area and auto-scroll. */
    public void log(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + time + "] " + msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(BidMaker::new);
    }
}

/**
 * Package-private client logic class used only by BidMaker.
 * Same behavior as before: respects starting bid, min increment, timer, final confirm.
 */
class BidMakerLogic {
    private static final String HOST = "localhost";
    private static final int PORT = 5000;

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private Thread listenThread;

    private String name = "";
    private final BidMaker gui;

    // State mirrored from server for validation & UI
    private String currentHighestBidder = "";
    private double currentHighestBid = 0.0;
    private double startingBid = 0.0;
    private double minIncrement = 0.0;
    private boolean waitingForFinal = false;

    public BidMakerLogic(BidMaker gui) {
        this.gui = gui;
    }

    /** Connects to server and sends JOIN. */
    public void join(String userName) {
        this.name = userName;
        try {
            socket = new Socket(HOST, PORT);
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out.println("JOIN|" + name);
            gui.afterJoinSuccess();
            startListener();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, "Failed to connect to server on port " + PORT);
        }
    }

    /** Place a bid if numeric and higher than current + min increment. */
    public void placeBid(String amountText) {
        try {
            double amount = Double.parseDouble(amountText.trim());
            if (amount < startingBid) {
                JOptionPane.showMessageDialog(null,
                        "Your bid must be at least starting bid $" + startingBid);
                return;
            }
            if (currentHighestBid > 0 && minIncrement > 0 &&
                    amount < currentHighestBid + minIncrement) {
                double need = currentHighestBid + minIncrement;
                JOptionPane.showMessageDialog(null,
                        "Your bid must be at least $" + need +
                                " (min increment $" + minIncrement + ")");
                return;
            }
            out.println("BID|" + name + "|" + amount);
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(null, "Invalid bid amount.");
        }
    }

    /** Confirm final bid (only allowed for last bidder upon request). */
    public void confirmFinal() {
        if (waitingForFinal && name.equals(currentHighestBidder)) {
            out.println("FINAL_CONFIRM|" + name);
            gui.enableConfirm(false);
        } else {
            JOptionPane.showMessageDialog(null,
                    "Only the last bidder can confirm when requested.");
        }
    }

    /** Listen to server messages and update GUI state safely. */
    private void startListener() {
        listenThread = new Thread(() -> {
            try {
                String msg;
                while ((msg = in.readLine()) != null) {
                    handle(msg);
                }
            } catch (IOException e) {
                JOptionPane.showMessageDialog(null, "Server disconnected.");
                gui.onDisconnected();
            }
        }, "client-listener");
        listenThread.start();
    }

    private void handle(String msg) {
        SwingUtilities.invokeLater(() -> {
            try {
                if (msg.startsWith("START|")) {
                    String[] p = msg.split("\\|");
                    String item = p[1];
                    startingBid = Double.parseDouble(p[2]);
                    minIncrement = Double.parseDouble(p[3]);
                    currentHighestBid = 0.0;
                    currentHighestBidder = "";
                    waitingForFinal = false;
                    gui.onStart(item, startingBid, minIncrement);
                } else if (msg.startsWith("BID|")) {
                    String[] p = msg.split("\\|");
                    String bidder = p[1];
                    double amount = Double.parseDouble(p[2]);
                    currentHighestBidder = bidder;
                    currentHighestBid = amount;
                    waitingForFinal = false;
                    gui.onBidUpdate(bidder, amount);
                    if (bidder.equals(name)) {
                        JOptionPane.showMessageDialog(null, "You're the highest bidder!");
                    }
                } else if (msg.startsWith("FINAL_REQUEST|")) {
                    String[] p = msg.split("\\|");
                    String last = p[1];
                    double amt = Double.parseDouble(p[2]);
                    waitingForFinal = true;
                    gui.onFinalRequest(last, amt);
                    if (last.equals(name)) {
                        gui.enableConfirm(true);
                        JOptionPane.showMessageDialog(null,
                                "Please confirm your final bid of $" + amt);
                    } else {
                        gui.enableConfirm(false);
                    }
                } else if (msg.startsWith("NEW_AUCTION|")) {
                    String[] p = msg.split("\\|");
                    String item = p[1];
                    startingBid = Double.parseDouble(p[2]);
                    minIncrement = Double.parseDouble(p[3]);
                    currentHighestBid = 0.0;
                    currentHighestBidder = "";
                    waitingForFinal = false;
                    gui.onNewAuction(item, startingBid, minIncrement);
                } else if (msg.startsWith("END|")) {
                    String[] p = msg.split("\\|");
                    String winner = p[1];
                    double amt = Double.parseDouble(p[2]);
                    waitingForFinal = false;
                    gui.onEnd(winner, amt);
                    JOptionPane.showMessageDialog(null,
                            "Auction ended. Winner: " + winner + " ($" + amt + ")");
                } else if (msg.startsWith("TIME|")) {
                    int seconds = Integer.parseInt(msg.split("\\|")[1]);
                    gui.onTimer(seconds);
                } else if (msg.startsWith("INFO|")) {
                    String info = msg.substring(5);
                    gui.log(info);
                    JOptionPane.showMessageDialog(null, info);
                }
            } catch (Exception ex) {
                gui.log("Malformed message ignored: " + msg);
            }
        });
    }

    /** Clean close when GUI exits. */
    public void close() {
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        try { if (in != null) in.close(); } catch (Exception ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (Exception ignored) {}
    }
}
