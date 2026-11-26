# Lightweight Email Client: Technical Documentation

**SMTP + POP3 Raw Socket Implementation with Advanced Bonus Features**

## 1. Project Overview

### 1.1 Description
This project implements a fully functional, text-based email client in Java 17 using **raw TCP sockets**. Unlike typical email applications that rely on high-level libraries like Jakarta Mail, this client manually implements the SMTP and POP3 application-layer protocols byte-by-byte. It communicates with a local Postfix/Dovecot mail server running on WSL2 to send, receive, persist, and manage emails.

### 1.2 Core Achievement
The project demonstrates deep systems-level programming by handling protocol framing, socket state management, TLS encryption handshakes, and concurrent background polling without any mail-specific framework abstractions.

### 1.3 Feature Summary
| Category | Features Implemented |
| :--- | :--- |
| **Mandatory (TRD)** | SMTP send, POP3 receive, JSON persistence, CLI menu, error handling, file logging, latency measurement |
| **Bonus B1** | Multiple account support with per-account isolated storage |
| **Bonus B2** | TLS/STARTTLS socket wrapping with self-signed cert trust |
| **Bonus B3** | Case-insensitive local inbox search across all fields |
| **Bonus B4** | Background thread periodic POP3 polling with independent sockets |

---

## 2. Concepts Covered

### 2.1 Networking & Protocols
-   **TCP Socket Programming**: Connection lifecycle, stream I/O, timeout configuration, resource management via try-with-resources
-   **OSI Layer 7 Protocol Implementation**: Manual SMTP (RFC 5321) and POP3 (RFC 1939) command/response parsing
-   **Protocol Framing**: CRLF line terminators, multi-line response handling, dot-stuffing for SMTP DATA
-   **TLS/SSL**: STARTTLS negotiation, SSL socket layering over existing TCP connections, certificate trust management
-   **Base64 Encoding**: AUTH LOGIN credential encoding without newline contamination

### 2.2 Concurrency & Thread Safety
-   **ScheduledExecutorService**: Fixed-delay periodic task scheduling
-   **Socket Thread Isolation**: Independent socket connections per background poll cycle (TRD §10 compliance)
-   **Synchronized File I/O**: Race-condition prevention with `CREATE_NEW` atomic file creation
-   **Daemon Threads**: Non-blocking JVM shutdown behavior
-   **Volatile Visibility**: Cross-thread flag communication

### 2.3 Software Engineering
-   **Modular Architecture**: Separation of network, model, storage, UI, and logging concerns
-   **Custom Exception Hierarchy**: `ProtocolException` vs `MailClientIOException` for precise error categorization
-   **Secure Logging**: Regex-based password redaction ensuring credentials never appear in log files
-   **Data Persistence**: Gson JSON serialization with per-account directory isolation
-   **Defensive Programming**: Input validation, graceful degradation, fail-fast security checks (no silent TLS downgrade)

---

## 3. Prerequisites

To understand and reproduce this project, the following knowledge is required:

### 3.1 Must-Know Before Starting
| Topic | Specific Knowledge Needed |
| :--- | :--- |
| **Java Fundamentals** | OOP, interfaces, exceptions, generics, streams, file I/O |
| **Java Networking** | `Socket`, `ServerSocket`, `InputStream`/`OutputStream`, `BufferedReader` |
| **TCP/IP Basics** | TCP handshake, ports, stream vs datagram, blocking I/O |
| **Text Protocols** | Request-response pattern, status codes, line-based framing |
| **WSL2 / Linux** | Basic bash commands, service management (`systemctl`/`service`) |
| **Maven** | Dependency management, build lifecycle, plugin configuration |

### 3.2 Learned During Implementation
| Topic | When Learned |
| :--- | :--- |
| SMTP/POP3 RFC specifics | Phases 3-4 |
| Raw email header parsing | Phase 3 |
| TLS socket layering | Phase B2 |
| Concurrent socket safety | Phase B4 |
| Atomic file operations | Phase B4 |

### 3.3 Toolchain Requirements
-   Java JDK 17+
-   Apache Maven 3.8+
-   WSL2 with Ubuntu 22.04 LTS
-   Postfix (SMTP) + Dovecot (POP3)
-   VSCodium or IntelliJ IDEA

---

## 4. System Architecture & Design

### 4.1 High-Level Architecture

```text
┌─────────────────────────────────────────────────────────────┐
│                      CLI User Interface                     │
│  Menu loop, account management, search, notifications       │
└──────────┬──────────────┬──────────────┬───────────────────┘
           │              │              │
┌──────────▼──────┐ ┌────▼────────┐ ┌───▼──────────────────┐
│  SmtpClient     │ │ Pop3Client  │ │  BackgroundPoller    │
│  SMTP protocol  │ │ POP3 proto  │ │  ScheduledExecutor   │
│  STARTTLS/TLS   │ │ STLS/TLS    │ │  Independent sockets │
└──────────┬──────┘ └────┬────────┘ └───┬──────────────────┘
           │              │              │
┌──────────▼──────────────▼──────────────▼──────────────────┐
│                  NetworkUtil (Shared)                      │
│  CRLF send, response parsing, TLS upgrade, socket connect  │
└──────────────────────────┬────────────────────────────────┘
                           │ TCP Socket
┌──────────────────────────▼────────────────────────────────┐
│              WSL2 Local Mail Server                        │
│  Postfix (:25) ←→ Dovecot (:110) ←→ testuser@localhost    │
└───────────────────────────────────────────────────────────┘

┌─────────────────────┐  ┌──────────────────────────────────┐
│   AccountManager    │  │         LocalMailbox             │
│   accounts.json     │  │  mailbox/<account>/msg_*.json    │
│   CRUD operations   │  │  Thread-safe save/load/search    │
└─────────────────────┘  └──────────────────────────────────┘

┌─────────────────────┐  ┌──────────────────────────────────┐
│  SimpleFileLogger   │  │        EmailMessage (POJO)       │
│  Timestamped log    │  │  Metadata + raw content          │
│  Password redaction │  │  Header parsing + body extract   │
└─────────────────────┘  └──────────────────────────────────┘
```

### 4.2 Component Roles

| Component | Package | Responsibility |
| :--- | :--- | :--- |
| **Main** | `com.emailclient` | Application entry point; instantiates and starts CliUI |
| **CliUI** | `.ui` | Interactive menu loop, user input handling, account selection, search interface, new-mail notifications |
| **SmtpClient** | `.network` | Full SMTP session: EHLO, STARTTLS, AUTH LOGIN, MAIL FROM, RCPT TO, DATA with dot-stuffing, QUIT |
| **Pop3Client** | `.network` | Full POP3 session: STLS, USER, PASS, LIST, RETR with byte-unstuffing, QUIT |
| **BackgroundPoller** | `.network` | Scheduled periodic POP3 polling with independent sockets, deduplication, callback notification |
| **NetworkUtil** | `.network` | Shared utilities: CRLF send, SMTP/POP3 response parsing, TLS socket factory, dot-stuffing, Base64 encoding |
| **AccountConfig** | `.model` | POJO for account credentials, host/port, TLS flag, poll interval |
| **EmailMessage** | `.model` | POJO for email data; includes raw email parser and body extractor |
| **SearchCriteria** | `.model` | Encapsulated search parameters with case-insensitive matching |
| **LocalMailbox** | `.storage` | Per-account JSON persistence, thread-safe save-if-new, search filtering |
| **AccountManager** | `.storage` | CRUD for `accounts.json`, default account creation |
| **SimpleFileLogger** | `.logger` | Timestamped file logging with regex-based password redaction |
| **ProtocolException** | `.exception` | Checked exception for protocol-level errors (wrong status codes, auth failures) |
| **MailClientIOException** | `.exception` | Checked exception for network/IO errors (timeout, connection refused) |

### 4.3 Data Flow Diagrams

**Send Email Flow:**
```text
User → CliUI → SmtpClient → [TLS Upgrade?] → NetworkUtil.sendLine()
  → Postfix:25 → Response parsed → Latency measured → Log written
```

**Background Poll Flow:**
```text
ScheduledExecutor → pollOnce() → NEW Pop3Client (independent socket)
  → Dovecot:110 → RETR messages → LocalMailbox.saveMessageIfNew()
  → Dedup check → Callback → CLI notification displayed
```

---

## 5. Implementation Phases

### Phase 1: Environment Setup & Protocol Validation
**Purpose:** Establish the local test environment and manually verify SMTP/POP3 protocol flows using telnet before writing any Java code. Ensures the mail server works correctly and provides reference request/response sequences.

### Phase 2: Core Infrastructure & Utilities
**Purpose:** Build the foundational reusable components (logger, network helpers, exceptions, data model) that all higher-level modules depend on. Establishes CRLF handling, timeout configuration, and password-safe logging from the start.

### Phase 3: POP3 Client & Local Storage
**Purpose:** Implement email retrieval and local JSON persistence first (as recommended by PRD learning order). Validates raw socket reading, multi-line response handling, email header parsing, and Gson serialization against the live Dovecot server.

### Phase 4: SMTP Client
**Purpose:** Implement email submission with full protocol compliance including AUTH LOGIN, dot-stuffing, and response code validation. Completes the send/receive loop and enables end-to-end testing.

### Phase 5: CLI User Interface & Integration
**Purpose:** Tie all modules into an interactive menu-driven application. Validates that all components work together, tests persistence across restarts, and establishes the baseline MVP satisfying all TRD mandatory requirements.

### Phase 6: Final Testing & Packaging
**Purpose:** Comprehensive validation against TRD testing criteria, performance measurement, security audit, and executable JAR packaging. Ensures production readiness and submission compliance.

### Phase B1: Multiple Accounts Support
**Purpose:** Transform the single-account hardcoded system into a data-driven multi-account architecture. Creates the foundation (`AccountConfig`, `AccountManager`, per-account storage) that all subsequent bonus features depend on. Highest refactoring risk — done first while codebase is simple.

### Phase B2: TLS / STARTTLS Socket Wrapping
**Purpose:** Add encrypted connection support by implementing STARTTLS/STLS protocol negotiation and SSL socket layering. Modifies the network layer that Background Poller (B4) will later consume. Includes self-signed certificate trust for local testing.

### Phase B3: Message Search Function
**Purpose:** Add local inbox search capability across subject, sender, recipient, and body. Independent of network changes, providing a low-risk feature between two complex phases. Benefits from B1's per-account scoping.

### Phase B4: Background Thread Periodic POP3 Poll
**Purpose:** Implement concurrent background mail checking with independent sockets per poll cycle. Most complex bonus feature — depends on B1 (account config), B2 (TLS-aware sockets), and requires thread-safe mailbox access. Satisfies the course's multi-threading learning objective.

---

## 6. Technology Stack

| Component | Technology | Version | Purpose |
| :--- | :--- | :--- | :--- |
| Language | Java | 17 LTS | Primary implementation |
| Build Tool | Apache Maven | 3.8+ | Dependency management, JAR packaging |
| JSON Library | Gson | 2.10.1 | Email serialization/deserialization |
| Mail Server (SMTP) | Postfix | 3.x | Local SMTP on WSL2 port 25 |
| Mail Server (POP3) | Dovecot | 2.3.x | Local POP3 on WSL2 port 110 |
| OS Platform | Windows + WSL2 | Ubuntu 22.04 LTS | Development environment |
| IDE | VSCodium | Latest | Code editing and debugging |
| Packaging | maven-shade-plugin | 3.5.1 | Executable fat JAR with dependencies |

---

## 7. Security Considerations

| Concern | Mitigation |
| :--- | :--- |
| Passwords in logs | `SimpleFileLogger.redact()` with regex patterns + explicit `[REDACTED]` in client code |
| Plaintext credentials | TLS/STARTTLS support (Phase B2); fail-fast if TLS required but unavailable |
| Silent TLS downgrade | Client throws `ProtocolException` if STARTTLS not advertised when `useTls=true` |
| Self-signed cert trust | Trust-all TrustManager explicitly documented as LOCAL TESTING ONLY |
| Thread safety | Synchronized mailbox writes, independent sockets per thread, volatile flags |
| Resource leaks | Try-with-resources on all sockets/streams, daemon threads, graceful executor shutdown |

---

## 8. Testing Summary

### Mandatory Tests Passed
- ✅ Send 3+ plain-text emails via raw SMTP sockets
- ✅ Fetch emails via raw POP3 sockets
- ✅ Local JSON persistence survives restart
- ✅ Wrong password handled gracefully
- ✅ Connection refused handled gracefully
- ✅ Invalid user input handled gracefully
- ✅ Send/retrieve latency measured and logged

### Bonus Feature Tests Passed
- ✅ Multiple accounts: add, remove, switch, isolated storage
- ✅ TLS: STARTTLS upgrade, STLS upgrade, direct TLS, fail-fast on missing TLS
- ✅ Search: keyword match across all fields, case-insensitive, account-scoped
- ✅ Background poll: independent sockets, deduplication, new-mail notification, graceful shutdown, error recovery, TLS-aware polling

---

## 9. Known Limitations

| Limitation | Rationale | Future Improvement |
| :--- | :--- | :--- |
| No MIME/attachment support | Out of scope per TRD; focus on socket/protocol fundamentals | Parse multipart/mixed boundaries |
| No IMAP support | POP3 sufficient for learning objectives; IMAP is significantly more complex | Stateful session management, UID tracking |
| Trust-all TLS | Acceptable for localhost self-signed certs only | Import server cert into Java truststore |
| Linear search | Adequate for local cache of hundreds of messages | In-memory inverted index for thousands |
| Single-threaded CLI | Background poller handles concurrency; CLI remains simple | Async UI framework if scaling to GUI |

---

## 10. How to Run

### Prerequisites

1. Java 17+ installed

2. WSL2 with Postfix and Dovecot running:

   ```bash
   # Install mail server packages
   sudo apt update
   sudo apt install postfix dovecot-pop3d -y

   # Start the services
   sudo service postfix start
   sudo service dovecot start

   # Verify they are listening on correct ports
   ss -tlnp | grep -E ':25|:110'
   ```

3. Test account configured (`testuser` / `test123`):

   ```bash
   # Create the user with a home directory
   sudo useradd -m -s /bin/bash testuser

   # Set the password to 'test123'
   echo "testuser:test123" | sudo chpasswd

   # Verify the user exists
   id testuser

   # To list all users on your WSL2 Linux system
   cat /etc/passwd | grep -E "/home|/bin/bash"
   ```

### Build & Execute
```powershell
mvn clean package
java -jar target/lightweight-email-client-1.0-SNAPSHOT.jar
```

### Quick Start
1.  Default test account auto-created on first run
2.  Use menu option **1** to send emails
3.  Use menu option **2** to fetch emails
4.  Use menu option **5** to manage multiple accounts
5.  Use menu option **6** to search inbox
6.  Background polling starts automatically for accounts with `pollIntervalSeconds > 0`
