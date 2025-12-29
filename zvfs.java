import java.io.File;
import java.io.IOException;

public class zvfs {
    public static void main(String[] args)
            throws IOException {

        // we have to decide between different options
        String operation = args[0];
        String fsName = args[1];
        String fileName = null;
        // java zvfs mkfs filesystem2.zvfs

        if (args.length == 3) {
            fileName = args[2];
        }

        String result = null;
        // implement error handling in filesystem class

        if (operation.equals("mkfs")) {
            FileSystem obj = new FileSystem();
            result = obj.mkfs(fsName);
        } else if (operation.equals("addfs")) {
            FileSystem obj = new FileSystem();
            result = obj.addfs(fsName, fileName);
        } else if (operation.equals("rmfs")) {
            FileSystem obj = new FileSystem();
            result = obj.rmfs(fsName, fileName);
        } else if (operation.equals("lsfs")) {
            FileSystem obj = new FileSystem();
            result = obj.lsfs(fsName);
        } else if (operation.equals("dfrgfs")) {
            FileSystem obj = new FileSystem();
            result = obj.dfrgfs(fsName);
        } else if (operation.equals("catfs")) {
            FileSystem obj = new FileSystem();
            result = obj.catfs(fsName, fileName);
        } else if (operation.equals("gifs")) {
            FileSystem obj = new FileSystem();
            result = obj.gifs(fsName);
        } else if (operation.equals("getfs")) {
            FileSystem obj = new FileSystem();
            result = obj.getfs(fsName, fileName);
        }

        else {
            result = "Error: Unknown operation '" + operation
                    + "'. Supported operations are: mkfs, addfs, getfs, rmfs, lsfs, dfrgfs, catfs, gifs.";
        }

        System.out.println(result);
    }

}