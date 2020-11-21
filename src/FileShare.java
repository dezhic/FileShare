import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Scanner;

public class FileShare {
    final static int SVR_TCP = 9999;
    final static int SVR_UDP = 9998;
    FileShareServer fileShareServer;
    FileShareClient fileShareClient;
    static Scanner scanner;

    public FileShare() throws IOException {
        File rootConfig = new File("share_root");
        if (!rootConfig.isFile()) {
            System.out.println("Missing share_root file");
        }
        Scanner fs = new Scanner(rootConfig);
        String root = fs.nextLine();
        fileShareServer = new FileShareServer(SVR_TCP, SVR_UDP, root, this);
        fileShareClient = new FileShareClient();
        scanner = new Scanner(System.in);
    }

    void cli() throws IOException {
        while (true) {
            // Read command
            System.out.print("> ");
            String[] cmd = scanner.nextLine().trim().split("\\s+");

            switch (cmd[0]) {
                case "login":
                    if (fileShareClient.isConnected) {
                        System.out.println("Already logged in to " + fileShareClient.svrIp);
                    }
                    if (cmd.length != 2) {
                        System.out.println("Invalid number of arguments");
                        break;
                    }

                    String[] loginInfo = cmd[1].split("@");  // cmd[1] format username@address
                    if (loginInfo.length != 2) {
                        System.out.println("Invalid argument \"" + cmd[1] + "\"");
                        System.out.println("Usage: login username@address");
                        break;
                    }
                    Host discoverdHost = null;
                    for (Host h : fileShareClient.hostList) {
                        if (h.name.equals(loginInfo[1])) {
                            discoverdHost = h;
                            break;
                        }
                    }
                    if (discoverdHost != null) {
                        login(loginInfo[0], discoverdHost.address.getHostAddress());
                        break;
                    }
                    login(loginInfo[0], loginInfo[1]);
                    break;

                case "disc":
                    if (cmd.length != 1) {
                        System.out.println("Invalid number of argument");
                        break;
                    }
                    discover();
                    break;

                case "download":
                    if (cmd.length != 2) {
                        System.out.println("Invalid number of argument");
                        break;
                    }

                    String filename = cmd[1];
                    fileShareClient.download(filename, "./Downloads");
                    break;

                case "upload":
                    if (cmd.length != 2) {
                        System.out.println("Invalid number of argument");
                        break;
                    }

                    String upFname = cmd[1];
                    fileShareClient.upload(upFname, "./Upload");
                    break;

                case "mkdir":
                    if (cmd.length != 2) {
                        System.out.println("Invalid number of argument");
                        break;
                    }
                    String dirName = cmd[1];
                    fileShareClient.mkdir(dirName);
                    break;

                case "detail":
                    if (cmd.length != 2) {
                        System.out.println("Invalid number of argument");
                        break;
                    }
                    fileShareClient.detail(cmd[1]);
                    break;

                case "rename":
                    if (cmd.length != 3) {
                        System.out.println("Invalid number of argument");
                        break;
                    }
                    fileShareClient.rename(cmd[1], cmd[2]);  // oldName, newName  whitespaces are not supported
                    break;

                case "del":
                    if (cmd.length != 2) {
                        System.out.println("Invalid number of argument");
                        break;
                    }
                    fileShareClient.delete(cmd[1]);
                    break;

                case "rmdir":
                    if (cmd.length != 2) {
                        System.out.println("Invalid number of argument");
                        break;
                    }
                    fileShareClient.rmdir(cmd[1]);
                    break;

                case "cd":
                    if (cmd.length != 2) {
                        System.out.println("Invalid number of argument");
                        break;
                    }
                    fileShareClient.cd(cmd[1]);
                    break;


                case "":
                    break;
                default:
                    System.out.println("Unknown command");
                    break;
            }
        }
    }

    void gui() {
        new GUI(this);
    }

    private void login(String username, String address) throws IOException {
        fileShareClient.login(username, address);
    }

    private void discover() throws IOException {
        fileShareClient.discover();
    }


    public static void main(String[] args) throws Exception {
        FileShare fileShare = new FileShare();
//        fileShare.cli();
        fileShare.gui();
    }
}
