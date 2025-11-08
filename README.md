# Auction-Bid-App-Updated

A simple **Java Swing** auction system with a **server** and **multiple clients**, communicating via plain-text messages over TCP sockets.

- **Server app**: `BidMaster`
- **Client app**: `BidMaker`

The server hosts an auction; clients join, place bids, and confirm the final winning bid.

---

## ✨ Features

- 💻 **GUI-based** server and client (Swing)
- 🔌 **Socket communication** over a fixed port `5000`
- 💲 **Starting bid** per auction item
- 📈 **Minimum bid increment** (min step)
- ⏱️ **Bid countdown timer** (auto-reset on each valid bid)
- ✅ **Final bid confirmation**
  - Only the **last highest bidder** can confirm when the server requests
- 🔁 **New Auction** button (server)
  - Start a new item without restarting apps
- 🔔 **Client notifications**
  - Popups for “You’re the highest bidder”, “You won”, errors, etc.
- 🧾 **Log panel** on both server and client
- 🧹 **Graceful handling of malformed / invalid messages**
  - No crashes on bad input

---

## 🧱 Architecture

Everything is kept very simple with only **two files**:

- `BidMaster.java`
  - `public class BidMaster` – server GUI
  - `class BidMasterLogic` – server networking + auction logic (inner class in same file)
- `BidMaker.java`
  - `public class BidMaker` – client GUI
  - `class BidMakerLogic` – client networking + state (inner class in same file)

Both sides use a **simple text protocol** based on pipe-separated messages, for example:

- `JOIN|Alice`
- `BID|Alice|250`
- `START|Laptop|100|10`  
  → item, starting bid, minimum increment
- `NEW_AUCTION|Phone|50|5`
- `FINAL_REQUEST|Alice|300`
- `FINAL_CONFIRM|Alice`
- `END|Alice|300`
- `TIME|27`
- `INFO|Some information message`

---

## 🚀 Getting Started

### 1. Prerequisites

- Java JDK 8+ (or newer)
- Any OS that can run Java (Windows, macOS, Linux)
