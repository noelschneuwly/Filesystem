from asyncio import constants
import sys
from struct import pack, unpack, calcsize
from pathlib import Path
import os
import time

# ----- Constants -----

# Sizes (fixed by the assignment)
HEADER_SIZE = 64  # size of header region [bytes]
ENTRY_SIZE = 64  # size of each file entry [bytes]
MAX_FILES = 32  # max number of file entries

# Header field OFFSETS (bytes from start of file)
MAGIC_OFFSET = 0  # 8 bytes, ASCII b"ZVFSDSK1"
VERSION_OFFSET = 8  # 1 byte, format version (1)
FLAGS_OFFSET = 9  # 1 byte, 0 = free spot exists, 1 = full

RESERVED0_OFFSET = 10  # 2 bytes, zero padding
FILE_COUNT_OFFSET = 12  # 2 bytes, number of active (non-deleted) files
FILE_CAPACITY_OFFSET = 14  # 2 bytes, total slots in entry table (should be 32)

FILE_ENTRY_SIZE_OFFSET = 16  # 2 bytes, size of each file entry (64)
RESERVED1_OFFSET = 18  # 2 bytes, zero padding

FILE_TABLE_OFFSET_OFFSET = 20  # 4 bytes, header field "file_table_offset"
DATA_START_OFFSET_OFFSET = 24  # 4 bytes, header field "data_start_offset"
NEXT_FREE_OFFSET_OFFSET = 28  # 4 bytes, header field "next_free_offset"

FREE_ENTRY_OFFSET = 32  # 4 bytes, header field "free_entry_offset"
DELETED_FILES_OFFSET = 36  # 2 bytes, header field "deleted_files"
RESERVED2_OFFSET = 38  # 26 bytes, zero padding (reserved2)

# Derived layout positions inside the .zvfs file
FILE_TABLE = HEADER_SIZE  # start of file entry table
FILE_TABLE_START = FILE_TABLE  # alias, same value
DATA_START = HEADER_SIZE + MAX_FILES * ENTRY_SIZE  # 64 * (1 + 32) = 2112

# Semantic constants / values
MAGIC_VALUE = b"ZVFSDSK1"
VERSION_VALUE = 1
ALIGNMENT = 64

# Max filesystem size (4 GB hard limit)
MAX_FS_SIZE = 4 * 1024 * 1024 * 1024  # 4 GiB

# Offsets inside a single 64-byte file entry
ENTRY_NAME_OFFSET = 0  # 32 bytes
ENTRY_START_OFFSET = 32  # 4 bytes
ENTRY_LENGTH_OFFSET = 36  # 4 bytes
ENTRY_TYPE_OFFSET = 40  # 1 byte
ENTRY_FLAG_OFFSET = 41  # 1 byte
ENTRY_RESERVED_OFFSET = 42  # 2 bytes
ENTRY_CREATED_OFFSET = 44  # 8 bytes
ENTRY_TAIL_OFFSET = 52  # 12 bytes reserved

# Additional constants
FILENAME_LENGTH = 32


# Note by Enrique : I used ChatGPT as a tutor to help me derive and structure the list of header- and layout-related constants (e.g. HEADER_SIZE, ENTRY_SIZE, MAGIC_OFFSET, FILE_COUNT_OFFSET, DATA_START, etc.) for zvfs.py based on the assignment’s header table. The tool suggested a consistent set of names and offsets, which I then checked against the PDF specification and integrated into our own code.

# ----- Helper functions  -----


# could potentially be used for all the writing into file
def write_entry(encoder, position, content, file):
    update = pack(f"{encoder}", content)
    file.seek(position)
    file.write(update)

    return file


# check if fs exists and update fsname if no .zvfs ending
def check_fs_update_fsname(fsname):
    fsname = (
        fsname.lower() + ".zvfs" if not fsname.lower().endswith(".zvfs") else fsname
    )
    fs_path = Path(f"{fsname}")
    assert fs_path.exists(), f"The given filesystem: {fsname} does not exist."
    return fsname


# search for a file in the fs and return its counter (position in the file entry table)
def search_file_in_fs_counter(fsname, filename):
    fsname_path = Path(f"{fsname}")
    with open(fsname_path, "r+b") as fsys:  # "r+b" = open in read and write binary mode
        counter = 0
        while counter < MAX_FILES:
            fsys.seek(HEADER_SIZE + ENTRY_SIZE * counter)
            name = unpack("32s", fsys.read(32))[0]
            clean_name = str(
                name.split(b"\x00", 1)[0], "utf-8"
            )  # according to textbook

            if clean_name == filename:
                break
            counter += 1

        if counter == MAX_FILES:  # we have exceeded the number of file entries.
            raise FileNotFoundError(
                f"File {filename} not found in filesystem {fsname}."
            )
        return counter


# ----- Command implementations -----


def do_mkfs(fsname):

    # B= unsigned char, H= unsigned short, I= unsigned int, Q= unsigned long long
    format_header = "8s B B H H H H H I I I I H 26s"
    format_entry_table = (
        "<32s I I B B H Q 12s"  # little endian --> cite the source in README
    )

    header = [b"ZVFSDSK1", 1, 0, 0, 0, 32, 64, 0, 64, 2112, 2112, 64, 0, b"\x00" * 26]
    entry_table = [bytes("", "utf-8"), 0, 0, 0, 0, 0, 0, b"\x00" * 12]

    if not fsname.endswith(".zvfs"):
        fsname += ".zvfs"

    with open(
        f"{fsname}", "wb"
    ) as f:  # open in write binary mode (and creates file if not exists already)
        file = pack(
            format_header, *header
        )  # pack(format, *data) converts data into binary according to format
        f.write(file)
        for i in range(0, 32):
            file2 = pack(format_entry_table, *entry_table)
            f.write(file2)

    return f"Created new Virtual Filesystem called {fsname}."


# Noël add some explanations for colleagues to get the workflow he thought of


def do_gifs(fsname):
    # First checkup that it works and the file is there, we should do that everywhere
    fs_path = Path(f"{fsname}")
    assert fs_path.exists(), f"The given filesystem: {fsname} does not exist."

    # according to the book, open always in "byte reader/writer"
    with open(fs_path, "rb") as file:
        # with seek you can "jump" to the position you want to to start reading/writing from there
        file.seek(12)

        # unpacking according to the textbook, the format "H" has to match the region you try to unpack (--> look at how it was stored), read 2 bytes from the file and unpack it returns tuple: (0, ) --> keep only first
        file_count = unpack("H", file.read(2))[0]
        file.seek(36)
        deleted_files = unpack("H", file.read(2))[0]
        free_entries = 32 - file_count - deleted_files

    # caluclates the size of the filesystem
    size = os.path.getsize(fs_path)

    return f"The {fsname} filesystem has got {file_count} active entries, {free_entries} free entries and {deleted_files} are marked as deleted. The total size of the filesystem is {size} Bytes."


# adds filet to fs: create new entry, write appropriate data, append file data to end of the file, ufpdate fs header info
def do_addfs(fsname, filename):
    fsname = check_fs_update_fsname(fsname)
    fs_path = Path(f"{fsname}")

    file_path = Path(f"{filename}")
    assert file_path.exists(), f"The given file: {filename} does not exist."

    # Additional check for filesystem size, that should not exceed 4GB
    filesys_size = os.path.getsize(fs_path)
    insert_file_size = os.path.getsize(file_path)

    if (filesys_size + insert_file_size) > MAX_FS_SIZE:
        return f"Cannot insert file: {filename} into filesystem: {fsname} as it would exceed the maximal capacity of 4GB of the filesystem"

    with open(fs_path, "r+b") as filesys, open(file_path, "rb") as file_to_add:

        # first check, if there any file entries left
        filesys.seek(FLAGS_OFFSET)
        flags = unpack("B", filesys.read(1))[0]
        if flags == 1:
            return "Cannot insert file in already full filesystem"

        # find free entry
        filesys.seek(FREE_ENTRY_OFFSET)
        free_entry = unpack("I", filesys.read(4))[0]

        if free_entry == 0:
            return "No file entries available anymore"

        # additional check for duplicate use the helper function find file:
        try:
            search_file_in_fs_counter(fsname, filename)
            return f"File {filename} already in {fsname}. Change name to insert into filesystem"
        except FileNotFoundError:
            # means that the file is not yet in the filesystem
            pass

        # find current numbers of present files
        filesys.seek(FILE_COUNT_OFFSET)
        file_count = unpack("H", filesys.read(2))[0]

        # current appending point
        filesys.seek(NEXT_FREE_OFFSET_OFFSET)
        current = unpack("I", filesys.read(4))[0]

        # file size and required padding
        file_size = os.path.getsize(file_path)
        padding = (ALIGNMENT - (file_size % ALIGNMENT)) % ALIGNMENT

        counter = 1
        free_entry_new = 0

        while counter < MAX_FILES:
            next_entry_offset = free_entry + ENTRY_SIZE * counter
            if next_entry_offset >= FILE_TABLE + MAX_FILES * ENTRY_SIZE:
                break

            filesys.seek(next_entry_offset)
            name = unpack("32s", filesys.read(FILENAME_LENGTH))[0]
            filesys.seek(next_entry_offset + ENTRY_FLAG_OFFSET)
            if name == b"\x00" * FILENAME_LENGTH:
                free_entry_new = next_entry_offset
                break

            counter += 1

        # Now update the actual file entry:

        # filename
        name_bytes = filename.encode("utf-8")
        if len(name_bytes) > (FILENAME_LENGTH - 1):
            return "File name must not exceed 31 characters. Please change filename"
        name_bytes += b"\x00" * (FILENAME_LENGTH - len(name_bytes))

        filesys.seek(free_entry)
        filesys.write(name_bytes)

        # starting point of the actual file
        write_entry("I", free_entry + FILENAME_LENGTH, current, filesys)

        # file size
        write_entry("I", free_entry + ENTRY_LENGTH_OFFSET, file_size, filesys)

        # type
        filesys.seek(free_entry + ENTRY_TYPE_OFFSET)
        filesys.write(pack("B", 0))

        # deleted spot
        filesys.seek(free_entry + ENTRY_FLAG_OFFSET)
        filesys.write(pack("B", 0))

        # padding
        filesys.seek(free_entry + ENTRY_RESERVED_OFFSET)
        filesys.write(b"\x00" * 2)

        # timestamp
        write_entry("Q", free_entry + ENTRY_CREATED_OFFSET, int(time.time()), filesys)

        # padding
        filesys.seek(free_entry + ENTRY_TAIL_OFFSET)
        filesys.write(b"\x00" * 12)

        # now we plug in the data
        file_bytes = file_to_add.read()  # read raw bytes
        filesys.seek(current)
        filesys.write(file_bytes)
        # write padding if necessary
        if padding:
            filesys.write(b"\x00" * padding)

        # Now we can adjust the metadata
        write_entry("H", FILE_COUNT_OFFSET, file_count + 1, filesys)

        new_next_free = current + file_size + padding
        write_entry("I", NEXT_FREE_OFFSET_OFFSET, new_next_free, filesys)

        filesys.seek(FREE_ENTRY_OFFSET)
        filesys.write(pack("I", free_entry_new))
        filesys.seek(FLAGS_OFFSET)
        filesys.write(pack("B", 1 if free_entry_new == 0 else 0))

    return f"Successfully added file {filename} to the filesystem {fsname}"


def do_getfs(fsname, filename):
    fsname = check_fs_update_fsname(fsname)
    fs_path = Path(f"{fsname}")

    file_path = Path(filename)

    counter = search_file_in_fs_counter(
        fsname, filename
    )  # get the counter of the file entry
    with open(fs_path, "rb") as f, open(file_path, "wb") as out:
        start_field = HEADER_SIZE + ENTRY_SIZE * counter  # go to start of file entry

        f.seek(start_field + 32)  # go to start offset field
        data_offset = unpack("I", f.read(4))[0]
        f.seek(start_field + 36)
        length = unpack("I", f.read(4))[0]
        f.seek(data_offset)
        content = unpack(f"{length}s", f.read(length))[0]
        # final = str(content, "utf-8")

        out.write(content)  # write content to disk

    return f"Returned file {filename} from {fsname} to Disk"


def do_rmfs(fsname, filename):
    fsname = check_fs_update_fsname(fsname)
    fsname_path = Path(f"{fsname}")

    # file_path = Path(filename)
    # assert file_path.exists(), f"The given file: {filename} does not exist."

    counter = search_file_in_fs_counter(
        fsname, filename
    )  # get the counter of the file entry
    with open(fsname_path, "r+b") as fsys:
        fsys.seek(12)  # go to file counter (in header)
        file_count = unpack("H", fsys.read(2))[0]

        fsys.seek(
            HEADER_SIZE + ENTRY_SIZE * counter + 41
        )  # counter is one behind, seeks flag position
        fsys.write(pack("B", 1))  # mark as deleted

        fsys.seek(12)
        fsys.write(pack("H", file_count - 1))  # decrease file count

        fsys.seek(36)
        deleted_files = unpack("H", fsys.read(2))[0]
        fsys.seek(36)  # have to go back here
        fsys.write(pack("H", deleted_files + 1))

    return f"File {filename} marked as deleted in filesystem {fsname}. Filesystem {fsname} now has {deleted_files + 1} files marked as deleted."


def do_lsfs(fsname):
    fsname = check_fs_update_fsname(fsname)
    fs_path = Path(f"{fsname}")
    result = []
    with open(fs_path, "rb") as filesys:
        for i in range(MAX_FILES):
            s = HEADER_SIZE + ENTRY_SIZE * i
            filesys.seek(s)
            name = unpack("32s", filesys.read(32))[0]
            if name == b"\x00" * 32:
                continue
            filesys.seek(s + 41)
            flag = unpack("B", filesys.read(1))[0]
            if flag == 1:
                continue
            filesys.seek(s + 36)
            size = unpack("I", filesys.read(4))[0]
            filesys.seek(s + 44)
            created = unpack("Q", filesys.read(8))[0]
            clean_name = name.split(b"\x00", 1)[0].decode("utf-8")
            result.append((clean_name, f"{size} Bytes", created))

    return result


def do_dfrgfs(fsname):
    fsname = check_fs_update_fsname(fsname)
    fs_path = Path(f"{fsname}")

    header_format = "8s B B H H H H H I I I I H 26s"
    entry_format = "<32s I I B B H Q 12s"

    with open(fs_path, "r+b") as fsys:
        # 1) read header
        fsys.seek(0)
        header_bytes = fsys.read(calcsize(header_format))
        (
            magic,
            version,
            flags,
            reserved0,
            file_count,
            file_capacity,
            file_entry_size,
            reserved1,
            file_table_offset,
            data_start_offset,
            next_free_offset,
            free_entry_offset,
            deleted_files_header,
            reserved2,
        ) = unpack(header_format, header_bytes)

        assert magic == b"ZVFSDSK1", "Not a ZVFS filesystem"
        assert file_capacity == MAX_FILES and file_entry_size == ENTRY_SIZE

        original_next_free = next_free_offset

        # 2) read all active entries + their data
        active_entries = []
        deleted_count = 0

        for i in range(MAX_FILES):
            entry_offset = HEADER_SIZE + ENTRY_SIZE * i
            fsys.seek(entry_offset)
            entry_bytes = fsys.read(ENTRY_SIZE)
            (
                name_raw,
                start,
                length,
                ftype,
                flag,
                res0,
                created,
                res_tail,
            ) = unpack(entry_format, entry_bytes)

            # empty slot -> ignore
            if name_raw == b"\x00" * 32:
                continue

            # deleted file -> count & skip (we remove it)
            if flag == 1:
                deleted_count += 1
                continue

            # active file -> store metadata + data
            fsys.seek(start)
            data = fsys.read(length)
            active_entries.append(
                {
                    "name": name_raw,
                    "length": length,
                    "type": ftype,
                    "created": created,
                    "data": data,
                }
            )

        # 3) clear file entry table
        fsys.seek(FILE_TABLE)
        fsys.write(b"\x00" * (MAX_FILES * ENTRY_SIZE))

        # 4) rebuild data region & entries compacted
        new_next_free = DATA_START

        for idx, entry in enumerate(active_entries):
            start = new_next_free
            # ensure 64-byte alignment
            if start % 64 != 0:
                start = ((start + 63) // 64) * 64

            # write file data
            fsys.seek(start)
            fsys.write(entry["data"])
            padding = (64 - (entry["length"] % 64)) % 64
            if padding:
                fsys.write(b"\x00" * padding)

            new_next_free = start + entry["length"] + padding

            # write entry
            entry_offset = HEADER_SIZE + ENTRY_SIZE * idx
            fsys.seek(entry_offset)
            fsys.write(
                pack(
                    entry_format,
                    entry["name"],  # 32s
                    start,  # I start
                    entry["length"],  # I length
                    entry["type"],  # B type
                    0,  # B flag (0 = active)
                    0,  # H reserved
                    entry["created"],  # Q created
                    b"\x00" * 12,  # 12s reserved tail
                )
            )

        # 5) renew header
        new_file_count = len(active_entries)
        new_deleted_files = 0

        if new_file_count < MAX_FILES:
            new_free_entry_offset = FILE_TABLE + ENTRY_SIZE * new_file_count
            new_flags = 0
        else:
            new_free_entry_offset = 0
            new_flags = 1

        freed_bytes = max(0, original_next_free - new_next_free)

        new_header = (
            magic,
            version,
            new_flags,
            reserved0,
            new_file_count,
            file_capacity,
            file_entry_size,
            reserved1,
            file_table_offset,
            data_start_offset,
            new_next_free,
            new_free_entry_offset,
            new_deleted_files,
            reserved2,
        )

        fsys.seek(0)
        fsys.write(pack(header_format, *new_header))

        # reduce file on disk so gifs shows reduced size
        fsys.truncate(new_next_free)

    return f"Defragmented {deleted_count} files and freed {freed_bytes} bytes of file data."


def do_catfs(fsname, filename):
    fsname = check_fs_update_fsname(fsname)
    fs_path = Path(f"{fsname}")

    with open(fs_path, "rb") as filesys:
        try:
            counter = search_file_in_fs_counter(fsname, filename)
        except FileNotFoundError:
            return f"No file named {filename} found in this filesystem."
        filesys.seek(HEADER_SIZE + ENTRY_SIZE * counter)
        entry_bytes = filesys.read(40)

        (name, start, length) = unpack("32s I I", entry_bytes)

        filesys.seek(start)
        content = filesys.read(length)
        content_final = unpack(f"<{length}s", content)[0]

        return str(content_final, "UTF-8")


def main():
    methods = {}
    for name, method in list(globals().items()):
        if name.startswith("do_"):
            methods[name.split("_")[1]] = method

    assert len(sys.argv) >= 3
    method = methods[sys.argv[1]]
    fsname = sys.argv[2]
    if len(sys.argv) > 3:
        filename = sys.argv[3]
        result = method(fsname, filename)
        return result

    return method(fsname)


if __name__ == "__main__":
    result = main()
    print(result)
