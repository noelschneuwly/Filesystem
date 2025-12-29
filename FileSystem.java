import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

public class FileSystem {

    // buffers for different regions (we'll fill these when reading/writing)
    private ByteBuffer header;
    private ByteBuffer entries;
    private ByteBuffer data;

    // ----- Basic layout sizes (from assignment) -----

    // size of header region [bytes]
    protected final int headerSize = 64;

    // size of each file entry [bytes]
    private final int entrySize = 64;

    // max number of file entries
    private final int maxFiles = 32;

    private final int reserved2 = 26;

    // ----- Header field OFFSETS (bytes from start of file) -----

    // 8 bytes, ASCII "ZVFSDSK1"
    private final int MAGIC_OFFSET = 0;

    // 1 byte, format version (1)

    private final int VERSION_OFFSET = 8;

    // 1 byte, 0 = free spot exists, 1 = full

    private final int FLAGS_OFFSET = 9;

    // 2 bytes, zero padding

    private final int RESERVED0_OFFSET = 10;

    // 2 bytes, number of active (non-deleted) files

    private final int FILE_COUNT_OFFSET = 12;

    // 2 bytes, total slots in entry table (should be 32)

    private final int FILE_CAPACITY_OFFSET = 14;

    // 2 bytes, size of each file entry (64)
    private final int FILE_ENTRY_SIZE_OFFSET = 16;

    // 2 bytes, zero padding
    private final int RESERVED1_OFFSET = 18;

    // 4 bytes, header field "file_table_offset"
    private final int FILE_TABLE_OFFSET_OFFSET = 20;

    // 4 bytes, header field "data_start_offset"
    private final int DATA_START_OFFSET_OFFSET = 24;

    // 4 bytes, header field "next_free_offset"
    private final int NEXT_FREE_OFFSET_OFFSET = 28;

    // 4 bytes, header field "free_entry_offset"
    private final int FREE_ENTRY_OFFSET = 32;

    // 2 bytes, header field "deleted_files"
    private final int DELETED_FILES_OFFSET = 36;

    // 26 bytes, zero padding (reserved2)
    private final int RESERVED2_OFFSET = 38;

    // should be dead code if we are sure about our offsets
    public void assert_header_layout() {
        try {
            assert MAGIC_OFFSET + 8 == VERSION_OFFSET : "Check that VERSION_OFFSET is correct";
            assert VERSION_OFFSET + 1 == FLAGS_OFFSET : "Check that FLAGS_OFFSET is correct";
            assert FLAGS_OFFSET + 1 == RESERVED0_OFFSET : "Check that RESERVED0_OFFSET is correct";
            assert RESERVED0_OFFSET + 2 == FILE_COUNT_OFFSET : "Check that FILE_COUNT_OFFSET is correct";
            assert FILE_COUNT_OFFSET + 2 == FILE_CAPACITY_OFFSET : "Check that FILE_CAPACITY_OFFSET is correct";
            assert FILE_CAPACITY_OFFSET + 2 == FILE_ENTRY_SIZE_OFFSET : "Check that FILE_ENTRY_SIZE_OFFSET is correct";
            assert FILE_ENTRY_SIZE_OFFSET + 2 == RESERVED1_OFFSET : "Check that RESERVED1_OFFSET is correct";
            assert RESERVED1_OFFSET + 2 == FILE_TABLE_OFFSET_OFFSET : "Check that FILE_TABLE_OFFSET_OFFSET is correct";
            assert FILE_TABLE_OFFSET_OFFSET + 4 == DATA_START_OFFSET_OFFSET
                    : "Check that DATA_START_OFFSET_OFFSET is correct";
            assert DATA_START_OFFSET_OFFSET + 4 == NEXT_FREE_OFFSET_OFFSET
                    : "Check that NEXT_FREE_OFFSET_OFFSET is correct";
            assert NEXT_FREE_OFFSET_OFFSET + 4 == FREE_ENTRY_OFFSET : "Check that FREE_ENTRY_OFFSET is correct";
            assert FREE_ENTRY_OFFSET + 4 == DELETED_FILES_OFFSET : "Check that DELETED_FILES_OFFSET is correct";
            assert DELETED_FILES_OFFSET + 2 == RESERVED2_OFFSET : "Check that RESERVED2_OFFSET is correct";
            assert RESERVED2_OFFSET + reserved2 == headerSize
                    : "Check that headerSize is correct and matches total size";
        } catch (AssertionError e) {
            System.out.println("Header layout assertion failed: " + e.getMessage());
        }
    }

    // ----- Derived layout positions inside the .zvfs file -----

    // start of file entry table (directly after header)
    private final int FILE_TABLE = headerSize;

    // alias, same value
    private final int FILE_TABLE_START = FILE_TABLE;

    // 64 * (1 + 32) = 2112 → header + 32 entries
    private final int DATA_START = headerSize + maxFiles * entrySize;

    // ----- Semantic constants / values -----

    // "magic" value that must be stored at offset 0
    private final byte[] MAGIC_VALUE = "ZVFSDSK1".getBytes(StandardCharsets.UTF_8);

    // version of the filesystem format
    private final int VERSION_VALUE = 1;

    // alignment for data region (all file data must be 64-byte aligned)
    private final int ALIGNMENT = 64;

    // Max filesystem size (4 GB hard limit from assignment) --> should not be
    // needed since our offset fields are 4 bytes only (--> max 2^32= 4GB)
    private final long MAX_FS_SIZE = 4L * 1024 * 1024 * 1024L; // 4 GiB

    private final int reserved1_file_entry = 12;

    // ----- Offsets inside a single 64-byte file entry -----

    // 32-byte UTF-8 name, null-terminated and padded with zeros
    private final int ENTRY_NAME_OFFSET = 0; // 32 bytes long

    // 4-byte start offset of file data in .zvfs
    private final int ENTRY_START_OFFSET = 32; // 4 bytes

    // 4-byte length (without padding)
    private final int ENTRY_LENGTH_OFFSET = 36; // 4 bytes

    // 1-byte type (0 in this assignment)
    private final int ENTRY_TYPE_OFFSET = 40; // 1 byte

    // 1-byte flag (0 = active, 1 = deleted)
    private final int ENTRY_FLAG_OFFSET = 41; // 1 byte

    // 2-byte reserved
    private final int ENTRY_RESERVED_OFFSET = 42; // 2 bytes

    // 8-byte UNIX timestamp
    private final int ENTRY_CREATED_OFFSET = 44; // 8 bytes

    // 12 bytes reserved tail
    private final int ENTRY_TAIL_OFFSET = 52; // 12 bytes reserved

    public void assert_file_entry() {
        try {
            assert ENTRY_NAME_OFFSET + 32 == ENTRY_START_OFFSET
                    : "Check that the Entryname length and Offset is correct";
            assert ENTRY_START_OFFSET + 4 == ENTRY_LENGTH_OFFSET : "Check that ENTRY_LENGTH_OFFSET is correct";
            assert ENTRY_LENGTH_OFFSET + 4 == ENTRY_TYPE_OFFSET : "Check that ENTRY_TYPE_OFFSET is correct";
            assert ENTRY_TYPE_OFFSET + 1 == ENTRY_FLAG_OFFSET : "Check that ENTRY_FLAG_OFFSET is correct";
            assert ENTRY_FLAG_OFFSET + 1 == ENTRY_RESERVED_OFFSET : "Check that ENTRY_RESERVED_OFFSET is correct";
            assert ENTRY_RESERVED_OFFSET + 2 == ENTRY_CREATED_OFFSET : "Check that ENTRY_CREATED_OFFSET is correct";
            assert ENTRY_CREATED_OFFSET + 8 == ENTRY_TAIL_OFFSET : "Check that ENTRY_TAIL_OFFSET is correct";
            assert ENTRY_TAIL_OFFSET + reserved1_file_entry == entrySize
                    : "Check that entrySize is correct and matches total size";
        } catch (AssertionError e) {
            System.out.println("File entry layout assertion failed: " + e.getMessage());
        }
    }

    // Note by Enrique : I used ChatGPT as a tutor to help me translate teh
    // constraints and logic from the python implementation to Java. I added "final"
    // to constants since they should not be modified.

    // constructor
    public FileSystem() {
        header = ByteBuffer.allocate(headerSize);
        entries = ByteBuffer.allocate(entrySize * maxFiles);
        data = ByteBuffer.allocate(0);
        // Ensure on-disk integer byte order matches Python (< little-endian)
        header.order(ByteOrder.LITTLE_ENDIAN);
        entries.order(ByteOrder.LITTLE_ENDIAN);
        data.order(ByteOrder.LITTLE_ENDIAN); // prbobably not needed since data is just raw bytes
    }

    // helper function (from StackOverflow)
    public void seek(ByteArrayInputStream input, int position)
            throws IOException {
        input.reset();
        input.skip(position);
    }

    public void layout_assertions() {
        assert_header_layout();
        assert_file_entry();
    }

    protected String mkfs(String fsName) {
        layout_assertions();

        String output = "Created new filesystem";
        byte[] magic = MAGIC_VALUE;
        byte version = VERSION_VALUE;
        byte flags = 0;
        short reserved0 = 0;
        short fileCount = 0;
        short fileCapacity = maxFiles;
        short fileEntrySize = entrySize;
        short reserved1 = 0;
        int fileTableOffset = FILE_TABLE_START;
        int dataStartOffset = DATA_START;
        int nextFreeOffset = DATA_START;
        int freeEntryOffset = FILE_TABLE_START;
        short deletedFiles = 0;
        byte[] reserved2 = new byte[26];

        // put() pushes bytes onto a ByteBuffer
        this.header.put(magic);

        header.put(version);
        header.put(flags);

        header.putShort(reserved0);
        header.putShort(fileCount);
        header.putShort(fileCapacity);
        header.putShort(fileEntrySize);
        header.putShort(reserved1);

        header.putInt(fileTableOffset);
        header.putInt(dataStartOffset);
        header.putInt(nextFreeOffset);
        header.putInt(freeEntryOffset);

        header.putShort(deletedFiles);

        header.put(reserved2);
        // how to write to a file
        try (FileOutputStream fos = new FileOutputStream(fsName)) {
            fos.write(header.array(), 0, headerSize); // need to write whole array, only headerSize bytes
        } catch (IOException e) {
            System.out.println("An error occurred: " + e.getMessage());
        }

        // for the file entres

        String name = "";
        int start = 0;
        int length = 0;
        byte type = 0;
        byte flag = 0;
        short reserved3 = 0;
        long timestamp = 0;
        byte[] reserved4 = new byte[12];

        for (int i = 0; i < maxFiles; i++) {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            entries.put(nameBytes);
            entries.put(new byte[32 - nameBytes.length]); // padding if the name is not 32 bytes ling
            entries.putInt(start);
            entries.putInt(length);
            entries.put(type);
            entries.put(flag);
            entries.putShort(reserved3);
            entries.putLong(timestamp);
            entries.put(reserved4);

        }

        try (FileOutputStream fos = new FileOutputStream(fsName, true)) { // according to geeksforgeeks --> //
                                                                          // otherwise it is overwritten
            fos.write(entries.array(), 0, entrySize * maxFiles);
        } catch (IOException f) {
            System.out.println("An error occurred: " + f.getMessage());
        }

        return output;
    }

    // Get information for a specified filesystem file.
    // The information printed out should be file name, number of files present (non
    // deleted), remaining free entries for new files (excluding deleted files), and
    // the number of files marked as deleted.
    // Moreover, print out the total size of the file.
    protected String gifs(String fsName) throws IOException {
        layout_assertions();

        String output = "display information for filesystem " + fsName + "didnt work";

        try (RandomAccessFile filesys = new RandomAccessFile(fsName, "r")) {
            // read header + entries
            header.clear();

            filesys.seek(0);
            filesys.readFully(header.array());

            int number_of_files = header.getShort(FILE_COUNT_OFFSET);
            int number_of_deleted = header.getShort(DELETED_FILES_OFFSET);
            int free_entries = header.getShort(FILE_CAPACITY_OFFSET) // 32 in our system
                    - number_of_files
                    - number_of_deleted;
            long total_size = filesys.length(); // in bytes
            // chose a different display than python version
            output = "File System: " + fsName + "\n" +
                    "Number of active files: " + number_of_files + "\n" +
                    "Number of deleted files: " + number_of_deleted + "\n" +
                    "Free entries for new files: " + free_entries + "\n" +
                    "Total size of the file: " + total_size + " bytes";
        }
        return output;
    }

    protected String addfs(String fsName, String fileName) throws IOException {
        layout_assertions();

        String output = "Added file " + fileName + " to filesystem " + fsName;

        // File size to add
        File insertFile = new File(fileName);
        long size = insertFile.length();
        int padding = (int) ((64 - (size % 64)) % 64);

        // first we have to read in the data from the file

        try (FileInputStream fis = new FileInputStream(fsName)) {
            fis.readNBytes(header.array(), 0, header.capacity());
            fis.readNBytes(entries.array(), 0, entries.capacity());

            // check if our file is already in the filesystem
            for (int i = 0; i < maxFiles; i++) {
                byte[] filename = new byte[32];
                entries.position(entrySize * i);
                entries.get(filename);

                String filenameString = new String(filename, StandardCharsets.UTF_8).trim();

                if (filenameString.equals(fileName)) {
                    return "File " + fileName + " already in filesystem. Change name to insert file.";
                }
            }

            File fileSystem = new File(fsName);
            long systemSize = fileSystem.length();
            long totalSize = systemSize + size;

            if (totalSize > MAX_FS_SIZE) {
                return "Cannot insert the file: " + fileName + " into filesystem: " + fsName
                        + " as it would exceed the maximal capacity of 4GB of the filesystem";
            }

        } catch (IOException e) {
            System.out.println("An error occurred: " + e.getMessage());
        }

        // we can now access all our field directly:
        // we stick to our python implementation:
        byte flags = header.get(FLAGS_OFFSET);
        if (flags == 1) {
            return "Cannot insert file in already full filesystem";
        }

        // free entry
        int freeEntryOffset = header.getInt(FREE_ENTRY_OFFSET);
        if (freeEntryOffset == 0) {
            return "No file entries available anymore";
        }

        int fileCount = header.getShort(FILE_COUNT_OFFSET) & 0xFFFF;
        int nextFreeOffset = header.getInt(NEXT_FREE_OFFSET_OFFSET);

        int counter = 1;
        int freeEntryOffsetNew = 0;

        while (counter < maxFiles) {
            int nextEntryOffset = freeEntryOffset + entrySize * counter;
            if (nextEntryOffset >= headerSize + maxFiles * entrySize) {
                break;
            }
            byte[] filename = new byte[32];
            entries.position(nextEntryOffset);
            entries.get(filename);

            boolean isEmpty = Arrays.equals(filename, new byte[32]);

            if (isEmpty) {
                freeEntryOffsetNew = nextEntryOffset;
                break;
            }

            counter++;
        }

        if (counter >= maxFiles) {
            return "No empty file entry in this filesystem";
        }

        byte[] filenameBytes = fileName.getBytes(StandardCharsets.UTF_8);
        if (filenameBytes.length > 31) {
            return "File name must not exceed 31 characters. Please change filename";
        }

        // write new filename --> ENTRY_NAME_OFFSET is 0
        entries.position(freeEntryOffsetNew + ENTRY_NAME_OFFSET);
        entries.put(filenameBytes);
        entries.put(new byte[32 - filenameBytes.length]);

        // the point where the actual data begins
        entries.position(freeEntryOffsetNew + ENTRY_START_OFFSET);
        entries.putInt(nextFreeOffset);

        // the file size
        entries.position(freeEntryOffsetNew + ENTRY_LENGTH_OFFSET);
        entries.putInt((int) (size)); //removed padding, file size should be without padding

        // timestamp
        entries.position(freeEntryOffsetNew + ENTRY_CREATED_OFFSET);
        entries.putLong(System.currentTimeMillis() / 1000L);

        // finally read in the data
        byte[] content = Files.readAllBytes(Paths.get(fileName));

        // finally we treat the metadata
        header.putShort(FILE_COUNT_OFFSET, (short) (fileCount + 1));
        header.putInt(NEXT_FREE_OFFSET_OFFSET, nextFreeOffset + content.length + padding);
        header.putInt(FREE_ENTRY_OFFSET, freeEntryOffsetNew);

        // flag handling not essentially needed, since we check if flag is 0 or 1 when
        // adding files
        byte flag;
        header.position(FLAGS_OFFSET);
        if (freeEntryOffsetNew == 0) {
            flag = 1;
        } else {
            flag = 0;
        }
        header.put(flag);

        // heaader, entries data save to file
        try (RandomAccessFile filesys = new RandomAccessFile(fsName, "rw")) {
            filesys.seek(0);
            filesys.write(header.array(), 0, headerSize);
            filesys.write(entries.array(), 0, entrySize * maxFiles);

            int filesysLength = header.getInt(NEXT_FREE_OFFSET_OFFSET);
            // int dataLength = filesysLength - DATA_START;

            // write only the actual data length
            filesys.seek(nextFreeOffset);
            filesys.write(content);
            filesys.write(new byte[padding]);

            // "truncates" the file to the new size of the filesystem, more passive in our case
            // only needed if we use other filesystems (smaller than before)
            filesys.setLength(filesysLength);

        } catch (IOException exep) {
            System.out.println("Error writing filesystem: " + exep.getMessage());
        }

        return output;
    }

    protected String getfs(String fsName, String fileName) throws IOException {
        String output = "It works";
        layout_assertions();

        try (RandomAccessFile filesys = new RandomAccessFile(fsName, "rw")) {
         // 1) Read header + entries into buffers
            header.clear();
            entries.clear();
            filesys.seek(0);
            filesys.readFully(header.array());
            filesys.readFully(entries.array());

            // 2) Search for the file in entry table
            int foundIndex = -1;
            int base = 0;
            for (int i = 0; i < maxFiles; i++) {
                base = i * entrySize;
                entries.position(base + ENTRY_NAME_OFFSET);
                byte[] nameBytes = new byte[32];
                entries.get(nameBytes);

                String cleanName = new String(nameBytes, StandardCharsets.UTF_8)
                        .split("\0", 2)[0];

                if (cleanName.equals(fileName)) {
                    foundIndex = i;
                    break;
                }
            }

            if (foundIndex == -1) {
                return "File " + fileName + " not found in filesystem " + fsName;
            }
            // 3) Read start offset and length
            // because we already read in enteries
            int start = entries.getInt(base + ENTRY_START_OFFSET);
            int length = entries.getInt(base + ENTRY_LENGTH_OFFSET);

            // 4) Read file content from file
            byte[] content = new byte[length];
            filesys.seek(start);
            filesys.readFully(content);

            // 5) Write file to disk
            try (FileOutputStream file_to_disk = new FileOutputStream(fileName)) {
                file_to_disk.write(content);
            } catch (IOException e) {
                System.out.println("An error occurred: " + e.getMessage());
            }
        }
        output = "Returned file " + fileName + " from filesystem " + fsName + " to disk.";
        return output;
    }


    protected String rmfs(String fsName, String fileName) throws IOException {
        // Mark file as deleted in the filesystem (set flag = 1) and updates header
        // counters.
        layout_assertions();
        String output;

        try (RandomAccessFile filesys = new RandomAccessFile(fsName, "rw")) {
            // 1) Read header + entries into buffers
            header.clear();
            entries.clear();
            filesys.seek(0);
            filesys.readFully(header.array());
            filesys.readFully(entries.array());

            // 2) Search for the file in entry table
            int foundIndex = -1;
            for (int i = 0; i < maxFiles; i++) {
                int base = i * entrySize;
                entries.position(base + ENTRY_NAME_OFFSET);
                byte[] nameBytes = new byte[32];
                entries.get(nameBytes);

                String cleanName = new String(nameBytes, StandardCharsets.UTF_8)
                        .split("\0", 2)[0];

                if (cleanName.equals(fileName)) {
                    foundIndex = i;
                    break;
                }
            }

            if (foundIndex == -1) {
                return "File " + fileName + " not found in filesystem " + fsName;
            }

            // 3) Mark the entry as deleted (det flag = 1)
            int entryPos = foundIndex * entrySize + ENTRY_FLAG_OFFSET;
            entries.position(entryPos);
            entries.put((byte) 1);

            // 4) Update header: file_count-- and deleted_files++
            short fileCount = header.getShort(FILE_COUNT_OFFSET);
            header.putShort(FILE_COUNT_OFFSET, (short) (fileCount - 1));

            short deleted = header.getShort(DELETED_FILES_OFFSET);
            header.putShort(DELETED_FILES_OFFSET, (short) (deleted + 1));

            // 5) Write header and entries back to disk (data region stays unchanged)
            filesys.seek(0);
            filesys.write(header.array());
            filesys.write(entries.array());

            output = "File " + fileName + " marked as deleted in filesystem " + fsName;
        }

        return output;
    }


    protected String lsfs(String fsName) throws IOException {
        // Lists all active (aka not flagged) files in the filesystem with size and
        // timestamp.
        layout_assertions();
        StringBuilder sb = new StringBuilder();
        boolean any = false;

        try (RandomAccessFile filesys = new RandomAccessFile(fsName, "r")) {
            header.clear();
            entries.clear();
            filesys.seek(0);
            filesys.readFully(header.array());
            filesys.readFully(entries.array());

            for (int i = 0; i < maxFiles; i++) {
                int base = i * entrySize;

                // read name
                entries.position(base + ENTRY_NAME_OFFSET);
                byte[] nameBytes = new byte[32];
                entries.get(nameBytes);

                // check if entry is empty
                boolean isEmpty = true;
                for (byte b : nameBytes) {
                    if (b != 0) {
                        isEmpty = false;
                        break;
                    }
                }
                if (isEmpty) {
                    continue;
                }

                // check flag (if deleted -> skip)
                byte flag = entries.get(base + ENTRY_FLAG_OFFSET);
                if (flag == 1) {
                    continue;
                }

                int length = entries.getInt(base + ENTRY_LENGTH_OFFSET);
                long created = entries.getLong(base + ENTRY_CREATED_OFFSET);

                String cleanName = new String(nameBytes, StandardCharsets.UTF_8)
                        .split("\0", 2)[0];

                any = true;
                sb.append(cleanName)
                        .append("  ")
                        .append(length)
                        .append(" Bytes  ")
                        .append("created=")
                        .append(created)
                        .append(System.lineSeparator());
            }
        }

        if (!any) {
            return "No active files in filesystem " + fsName;
        }
        return sb.toString();
    }

    protected String dfrgfs(String fsName) throws IOException {
        // Removes deleted files and compacts entries + data. (we load all used data
        // into memory and rewrite it compactly.)
        layout_assertions();
        String output;

        try (RandomAccessFile filesys = new RandomAccessFile(fsName, "rw")) {
            // 1) Read header + entries
            header.clear();
            entries.clear();
            filesys.seek(0);
            filesys.readFully(header.array());
            filesys.readFully(entries.array());

            int oldNextFree = header.getInt(NEXT_FREE_OFFSET_OFFSET);

            // size of data currently in use
            int oldDataSize = Math.max(0, oldNextFree - DATA_START);

            // read old data into a buffer
            data = ByteBuffer.allocate(oldDataSize);
            data.order(ByteOrder.LITTLE_ENDIAN);
            if (oldDataSize > 0) {
                filesys.seek(DATA_START);
                filesys.readFully(data.array(), 0, oldDataSize);
            }

            // 2) Collect active entries + respective data
            class EntryMeta {
                byte[] name;
                int length;
                long created;
                byte type;
                byte[] fileData;
            }

            java.util.List<EntryMeta> active = new java.util.ArrayList<>();
            int deletedCount = 0;

            for (int i = 0; i < maxFiles; i++) {
                int base = i * entrySize;

                // read name
                entries.position(base + ENTRY_NAME_OFFSET);
                byte[] nameBytes = new byte[32];
                entries.get(nameBytes);

                // empty slot?
                boolean isEmpty = true;
                for (byte b : nameBytes) {
                    if (b != 0) {
                        isEmpty = false;
                        break;
                    }
                }
                if (isEmpty) {
                    continue;
                }

                byte flag = entries.get(base + ENTRY_FLAG_OFFSET);
                if (flag == 1) {
                    // deleted file -> drop it
                    deletedCount++;
                    continue;
                }

                int start = entries.getInt(base + ENTRY_START_OFFSET);
                int length = entries.getInt(base + ENTRY_LENGTH_OFFSET);
                long created = entries.getLong(base + ENTRY_CREATED_OFFSET);
                byte type = entries.get(base + ENTRY_TYPE_OFFSET);

                // read data from old data buffer (relative to DATA_START)
                int relOffset = start - DATA_START;
                byte[] fileData = new byte[length];
                if (relOffset >= 0 && relOffset + length <= oldDataSize) {
                    System.arraycopy(data.array(), relOffset, fileData, 0, length);
                }

                EntryMeta em = new EntryMeta();
                em.name = nameBytes;
                em.length = length;
                em.created = created;
                em.type = type;
                em.fileData = fileData;
                active.add(em);
            }

            // 3) Clear entries buffer)
            Arrays.fill(entries.array(), (byte) 0);

            // 4) Rebuild data & entries
            ByteBuffer newData = ByteBuffer.allocate(oldDataSize);
            int writeRelOffset = 0; // relative to DATA_START
            int newNextFree = DATA_START;

            for (int i = 0; i < active.size(); i++) {
                EntryMeta em = active.get(i);

                // align to 64 bytes relative to DATA_START
                int alignedRel = ((writeRelOffset + ALIGNMENT - 1) / ALIGNMENT) * ALIGNMENT;
                int start = DATA_START + alignedRel;

                // write data into newData
                newData.position(alignedRel);
                newData.put(em.fileData);
                int padding = (ALIGNMENT - (em.length % ALIGNMENT)) % ALIGNMENT;
                if (padding > 0) {
                    newData.put(new byte[padding]);
                }

                writeRelOffset = alignedRel + em.length + padding;
                newNextFree = DATA_START + writeRelOffset;

                // write entry at index "i"
                int base = i * entrySize;
                entries.position(base + ENTRY_NAME_OFFSET);
                entries.put(em.name);
                entries.position(base + ENTRY_START_OFFSET);
                entries.putInt(start);
                entries.position(base + ENTRY_LENGTH_OFFSET);
                entries.putInt(em.length);
                entries.position(base + ENTRY_TYPE_OFFSET);
                entries.put(em.type);
                entries.position(base + ENTRY_FLAG_OFFSET);
                entries.put((byte) 0); // active
                entries.position(base + ENTRY_CREATED_OFFSET);
                entries.putLong(em.created);
            }

            // 5) Update header
            int newFileCount = active.size();
            int freedBytes = Math.max(0, oldNextFree - newNextFree);

            header.putShort(FILE_COUNT_OFFSET, (short) newFileCount);
            header.putShort(DELETED_FILES_OFFSET, (short) 0);
            header.putInt(NEXT_FREE_OFFSET_OFFSET, newNextFree);

            if (newFileCount < maxFiles) {
                int newFreeEntryOffset = FILE_TABLE_START + newFileCount * entrySize;
                header.putInt(FREE_ENTRY_OFFSET, newFreeEntryOffset);
                header.put(FLAGS_OFFSET, (byte) 0); // still space left
            } else {
                header.putInt(FREE_ENTRY_OFFSET, 0);
                header.put(FLAGS_OFFSET, (byte) 1); // full
            }

            // 6) Write everything back
            filesys.seek(0);
            filesys.write(header.array());
            filesys.write(entries.array());

            if (writeRelOffset > 0) {
                filesys.seek(DATA_START);
                filesys.write(newData.array(), 0, writeRelOffset);
            }

            filesys.setLength(newNextFree);

            output = "Defragmented " + deletedCount + " files and freed " + freedBytes + " bytes of file data.";
        }

        return output;
    }

    protected String catfs(String fsName, String fileName) throws IOException {
        // Prints the contents of a file stored inside the filesystem.
        layout_assertions();

        String output;
        byte[] content = null;

        try (RandomAccessFile filesys = new RandomAccessFile(fsName, "r")) {
            header.clear(); // doesnt delete the data, just resets position
            entries.clear();
            filesys.seek(0);
            filesys.readFully(header.array());
            filesys.readFully(entries.array());

            // 1) Find the file entry
            int foundIndex = -1;
            for (int i = 0; i < maxFiles; i++) {
                int base = i * entrySize;
                entries.position(base + ENTRY_NAME_OFFSET);
                byte[] nameBytes = new byte[32];
                entries.get(nameBytes); // reads 32 bytes for the name

                String cleanName = new String(nameBytes, StandardCharsets.UTF_8)
                        .split("\0", 2)[0];

                byte flag = entries.get(base + ENTRY_FLAG_OFFSET);
                if (flag == 1) {
                    continue; // deleted, skip
                }

                if (cleanName.equals(fileName)) {
                    foundIndex = i;
                    break;
                }
            }

            if (foundIndex == -1) {
                throw new IOException("File " + fileName + " not found in filesystem " + fsName);
            }

            // 2) Read start offset and length
            int base = foundIndex * entrySize;
            int start = entries.getInt(base + ENTRY_START_OFFSET);
            int length = entries.getInt(base + ENTRY_LENGTH_OFFSET);

            // 3) Read file content from data region
            content = new byte[length];
            filesys.seek(start);
            filesys.readFully(content);
        }

        // can we assume UTF-8 content? - we did in python version...
        // Elia: would say so?!? we nearly only looked at text files in the assignment and
        // lectures

        // Answer Noel: Lets assume UTF-8 content, we did not look at any other stuff in
        // the lectures
        output = new String(content, StandardCharsets.UTF_8);
        return output;
    }
}