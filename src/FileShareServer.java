import javax.swing.*;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Scanner;

public class FileShareServer {

    FileShare main;  // Pointer to the main class
    ServerSocket svrSocket;
    DatagramSocket udpSocket;
    File sharedRoot;

    public FileShareServer(int tcpPort, int udpPort, String rootPath, FileShare main) throws IOException {
        System.out.println("Server created");
        this.main = main;
        svrSocket = new ServerSocket(tcpPort);
        udpSocket = new DatagramSocket(udpPort);
        sharedRoot = new File(rootPath);

        // Validate sharedRoot
        if (!sharedRoot.exists()) {
            sharedRoot.mkdirs();
        } else if (!sharedRoot.isDirectory()) {
            System.out.println("Invalid configuration for shared root");
            System.exit(1);
        }

        // Create listening thread
        Thread listeningThread = new Thread(() -> {
            System.out.printf("Listening at port %d...", tcpPort);
            while (true) {
                try {
                    Socket clSocket = svrSocket.accept();

                    // Create connection thread
                    Thread connThread = new Thread(() -> {
                        try {
                            // User authentication
                            DataOutputStream dout = new DataOutputStream(clSocket.getOutputStream());
                            DataInputStream din = new DataInputStream((clSocket.getInputStream()));
                            byte[] buffer = new byte[1024];

                            // Request username
                            System.out.println("waiting for username...");
                            String username = Message.tcpReceive(din).body;
                            // Request password
                            System.out.println("waiting for password...");

                            Message.tcpSend(dout, new Message(MessageType.REQUEST, "Password: "));

                            String password = Message.tcpReceive(din).body;
                            // Verify
                            if (verifyUser(username, password)) {

                                // Send success message
                                Message.tcpSend(dout, new Message(MessageType.SUCCESS, "Login successful"));
                                // Start serving the client socket
                                serve(clSocket);

                            } else {
                                // Abort the connection
                                Message.tcpSend(dout, new Message(MessageType.FAILURE, "Wrong username or password"));
                            }
                        } catch (Exception e) {
                            // System.err.println("connection dropped.");
                            e.printStackTrace();
                        }
                    });
                    connThread.start();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        listeningThread.start();

        // Create UDP listening thread
        Thread udpListen = new Thread(() -> {
            while (true) {
                try {
//                    System.out.println("Waiting for UDP connection");
                    Object[] udpRcvd = Message.udpReceive(udpSocket);  // {Message, InetAddress, int}
                    Message msg = (Message) udpRcvd[0];
                    InetAddress srcAdd = (InetAddress) udpRcvd[1];
                    int srcPort = (Integer) udpRcvd[2];

                    if (msg.type == MessageType.DISCOVERY) {
                        Message.udpSend(udpSocket, srcAdd, srcPort, new Message(MessageType.SUCCESS, InetAddress.getLocalHost().getHostName()));
                    }
                } catch (NegativeArraySizeException e) {
                    // Corrupted user datagram received
                    // Ignore
                } catch (StreamCorruptedException e) {
                    // Corrupted user datagram received
                    // Ignore
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        udpListen.start();
    }

    private void serve(Socket clSocket) throws IOException {
        try {
            System.out.printf("Established a connection to host %s:%d\n\n", clSocket.getInetAddress(),
                    clSocket.getPort());
            File cwd = new File(sharedRoot.getCanonicalPath());
            // Get stream
            DataInputStream din = new DataInputStream(clSocket.getInputStream());
            DataOutputStream dout = new DataOutputStream(clSocket.getOutputStream());

            while (!Thread.currentThread().isInterrupted()) {
                // Get request type
                Message msg = Message.tcpReceive(din);

                switch (msg.type) {
                    case DOWNLOAD:
                        String filename = msg.body;
                        sendFile(dout, cwd.getCanonicalPath() + File.separator + filename);
                        break;

                    case UPLOAD:
                        String filenameWithPath = msg.body;
                        receiveFile(din, dout, cwd.getCanonicalPath() + File.separator + filenameWithPath);
                        break;

                    case MKDIR:
                        String dirName = msg.body;
                        try {
                            makeDirectory(cwd.getCanonicalPath(), dirName);
                            Message.tcpSend(dout, new Message(MessageType.SUCCESS, ""));
                        } catch (Exception e) {
                            Message.tcpSend(dout, new Message(MessageType.FAILURE, e.getMessage()));
                        }
                        break;

                    case DETAIL:
                        detail(dout, cwd.getCanonicalPath() + File.separator + File.separator + msg.body);  // msg.body is the filename
                        break;

                    case RENAME:
                        String[] names = msg.body.split("\\?");  // names[0] == oldName, names[1] == newName
                        rename(dout, cwd.getCanonicalPath() + File.separator + names[0], cwd.getCanonicalPath() + File.separator + names[1]);
                        break;

                    case DELETE:
                        deleteFile(dout, cwd.getCanonicalPath() + File.separator + msg.body);
                        break;

                    case RMDIR:
                        deleteDirectory(dout, cwd.getCanonicalFile() + File.separator + msg.body);
                        break;

                    case TREE:
                        sendTree(dout);
                        break;

                }
            }
        } catch (SocketException e) {
            // Client dropped
            Thread.currentThread().interrupt();
        }
    }


    private void makeDirectory(String path, String dirName) throws Exception {
        new File(path, dirName).mkdirs();
    }

    private void sendFile(DataOutputStream dout, String filename) throws IOException {
        File f = new File(filename);
        if (!f.exists()) {
            Message.tcpSend(dout, new Message(MessageType.FAILURE, "File not exist"));
            return;
        } else if (f.isDirectory()) {
            Message.tcpSend(dout, new Message(MessageType.FAILURE, "Cannot send directory"));
            return;
        }
        // Start file transmission
        Message.tcpSend(dout, new Message(MessageType.SUCCESS, "Start transmission"));
        FileInputStream fin = new FileInputStream(f);
        byte[] buffer = new byte[1024];
        // Transmit fSize
        long fSize = f.length();
        dout.writeLong(fSize);
        // Transmit file content
        while (fSize > 0) {
            int read = fin.read(buffer);
            dout.write(buffer, 0, read);
            fSize -= read;
        }

    }

    private void receiveFile(DataInputStream din, DataOutputStream dout, String filenameWithPath) throws
            IOException {
        FileOutputStream fout = null;
        try {
            // Create directories and file
            File f = new File(filenameWithPath);
            File dir = f.getParentFile();
            dir.mkdirs();
            // Start transmission
            fout = new FileOutputStream(f);
            Message.tcpSend(dout, new Message(MessageType.SUCCESS, "Start transmission"));
        } catch (Exception e) {
            Message.tcpSend(dout, new Message(MessageType.FAILURE, e.getMessage()));
            return;
        }
        byte[] buffer = new byte[1024];
        long fSize = din.readLong();
        while (fSize > 0) {
            int read = din.read(buffer, 0, Math.min(((int) fSize), buffer.length));
            fout.write(buffer, 0, read);
            fSize -= read;
        }
        fout.close();
    }

    private void deleteFile(DataOutputStream dout, String file) throws IOException {
//        System.out.println("del: " + file);
        try {
            new File(file).delete();
            Message.tcpSend(dout, new Message(MessageType.SUCCESS, ""));
        } catch (Exception e) {
            Message.tcpSend(dout, new Message(MessageType.FAILURE, e.getMessage()));
        }
    }

    private void deleteDirectory(DataOutputStream dout, String filename) throws IOException {
        File file = new File(filename);
        if (file.isFile()) {
            Message.tcpSend(dout, new Message(MessageType.FAILURE, "This is not a directory"));
        }
        deleteDirectory(file);
        Message.tcpSend(dout, new Message(MessageType.SUCCESS, ""));
    }

    private boolean deleteDirectory(File dir) {
        File[] allContents = dir.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        return dir.delete();
    }


    private void rename(DataOutputStream dout, String fname, String newfname) throws IOException {
        try {
            File new_fname = new File(newfname);
            if (new_fname.exists()) {
                Message.tcpSend(dout, new Message(MessageType.FAILURE, "File/Folder with the same name exists"));
                return;
            }
            new File(fname).renameTo(new_fname);
            Message.tcpSend(dout, new Message(MessageType.SUCCESS, ""));
        } catch (Exception e) {
            Message.tcpSend(dout, new Message(MessageType.FAILURE, e.getMessage()));
        }

    }

    private void detail(DataOutputStream dout, String filename) throws IOException {
        File file = new File(filename);
        if (!file.exists()) {
            Message.tcpSend(dout, new Message(MessageType.FAILURE, "File not exists"));
        }
        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");

        String detailStr = "";
        detailStr += ("name : " + file.getName() + "\n");
        detailStr += ("size (bytes) : " + file.length() + "\n");
        detailStr += ("absolute path? : " + file.isAbsolute() + "\n");
        detailStr += ("exists? : " + file.exists() + "\n");
        detailStr += ("hidden? : " + file.isHidden() + "\n");
        detailStr += ("dir? : " + file.isDirectory() + "\n");
        detailStr += ("file? : " + file.isFile() + "\n");
        detailStr += ("modified (timestamp) : " + sdf.format(file.lastModified()) + "\n");
        detailStr += ("readable? : " + file.canRead() + "\n");
        detailStr += ("writable? : " + file.canWrite() + "\n");
        detailStr += ("executable? : " + file.canExecute() + "\n");
        detailStr += ("parent : " + file.getParent() + "\n");
        detailStr += ("absolute file : " + file.getAbsoluteFile() + "\n");
        detailStr += ("absolute path : " + file.getAbsolutePath() + "\n");
        detailStr += ("canonical file : " + file.getCanonicalFile() + "\n");
        detailStr += ("canonical path : " + file.getCanonicalPath() + "\n");

        Message.tcpSend(dout, new Message(MessageType.SUCCESS, detailStr));
    }

    private boolean verifyUser(String username, String password) {
//        System.out.println("Received username: " + username);
//        System.out.println("Received password: " + password);

        File authFile = new File("authorized_users");
        if (authFile.isFile()) {
            try {
                FileInputStream fin = new FileInputStream(authFile);
                Scanner fsc = new Scanner(fin);
                String line;
                while (fsc.hasNextLine()) {
                    line = fsc.nextLine().trim();
                    String[] userInfo = line.split(" ", 2);
                    if (userInfo.length != 2) {
                        continue;   // In case of corrupted authorized_user file
                    }
                    if (userInfo[0].equals(username) && userInfo[1].equals(password)) {
                        return true;
                    }
                    fin.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    private void sendTree(DataOutputStream dout) throws IOException {
        try {
            JTree tree = GUI.buildTree(sharedRoot);
            Message.tcpSend(dout, new Message(MessageType.SUCCESS, "Start tree transmission"));
            ObjectOutputStream oos = new ObjectOutputStream(dout);
            oos.writeObject(tree);
        } catch (Exception e) {
            Message.tcpSend(dout, new Message(MessageType.FAILURE, e.getMessage()));
        }
    }

}
