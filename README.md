# Assignment 3

# Introduction

This repository is based on a project at University of Zurich, Department of Informatics in Fall Term 25. The task was to design a virtual filesystem in two programming languages. The project was originally hosted on GitLab and is has later been transfered to GitHub. This README File gives detailed explanations about the setup and the design of the implementation as well as difficulties that occurred during the process. 

# Explanations

## Step 01

Generally our implementation sticks as closely as possible to the introduction section in the textbook: "Software Design by Example" by Greg Wilson. We preserved the strategy of using the struct module for all our (python) implementations.

### Helper Functions:

#### def write_entry(encoder, position, content, file):

Writes a single packed field into an open binary file, with a given encoder. It needs to be called in an already opened file that allows writing.
Packs content using the struct format string encoder, seeks the position in file and writes the resulting bytes. The function then returns the (mutated) file object.

#### def check_fs_update_fsname(fsname):

Follows to main goals: It normalizes the filesystem name and also checks if the filesystem exists.
The name of the filesystem is lowercase and has a ".zvfs" ending. If that is not already the case it is afterwards.
Then we check if the filesystem (more precisely it's path) exists.
If that is the case we return the new, correct name of the filesystem.

#### def search_file_in_fs_counter(fsname, filename):

Locate a file entry inside the filesystem and return its entry index.
Searches the filesystem's directory table for an entry whose name equals the filename and return the entry index (here our counter).
It is used by operations that need the entry position of a specific file. (e.g. getfs/rmfs).

Implemented that function to make the corresponding functions more clear. Uses a one-behind counter to return the correct fileentry.
If the counter has exceeded the number of (possible) files it raises a FileNotFoundError, for correct handling in the main functions.

### def do_mkfs(fsname):

This is the basic function to create a new virtual filesystem for the python solution. It can be used by input via terminal. It first defines the format (that is used for packing a file into a bytes object by the struct.pack() function) for the header:

```json
    format = "8s B B H H H H H I I I I H 26s"
```

This holds space for exactly 64 bytes and meets all required fields according to the assignment.

Secondly the format for the file entry is defined as follows:

```json
    format2 = "32s I I B B H Q 12s"
```

Which again holds space for another 64 bytes per file entry. We then also type in some required or just filler values for all the fields and open a new file with the given filesystem-filename in "write binary" mode and pack all the content of the header into this binary file using the above mentioned struct.pack() method. The same is then done for the file entries, but 32 times to have all 32 file entries (which is the maximum for our filesystem) already there, as a basis for later insertion of possible files. We finally return a message, that a new filesystem with the given name has been created.

### def do_gifs(fsname):

This method has to give all necessary information about a state of a filesystem. First it checks, if the desired filesystem exists. If it does the functionality starts, otherwise an AssertionError is raised.

It opens the given file in "binary read (rb)" mode and first jumps to byte 12, where the file_count integer is:

```json
    file.seek(12)
```

From there the function reads two bytes and unpacks it as an integer, which results in a tuple (e.g: (32, )). This tuple is then accessed and stored in the variable file_count:

```json
    file_count = unpack("H", file.read(2))[0]
```

The same is done for the field "deleted_files", which is present at another byte region. Finally the free entries are calculated as the difference between the total available file entries and the deleted files and the active files and stored in the variable free_entries.
Finally the total size of the filesystem file is calculated (using a method references in the sources section) and all information is returned.

### def do_addfs(fsname, filename):

When adding a file, three major things have to be taken into account:

- Metadata adjustment

  - check if after this file insertion, there will be no free spot anymore --> ev. adjust flags
  - file_count field += 1
  - adjust next_free_offset --> append to current offset the size of the inserted file

- New file entry

  - is there a deleted file entry --> insert the new file entry there
  - otherwise use an empty one

- Add the data
  - check for proper alignment

Due to all these requirements, the method is also more complicated than previous ones. Again the method is called via the command line where the filesystem name and the file to add are both mentioned as additional command line arguments. First both the filesystem and the file to add are tested on existance and if the tests pass we open both of them in binary read and/or write mode.

We then do an additional check:

```json
  filesys.seek(9)
        flags = unpack("B", filesys.read(1))[0]
        if flags == 1:
            return "Cannot insert file in already full filesystem"
```

Checks the flags field if there is a free spot left in the filesystem.

Then, a few important metadata values are evaluated:

Then the current next free entry offset is evaluated and stored in a variable called free_entry. If this free_entry equals 0, this is again a message for a full filesystem: A Warning is returned and the procedure gets interrupted.
Thirdly the current active files are evaluated together with the current appending point (e.g. the current "end of all bytes") and finally the size of the file to add is evaluated:

```json
  file_size = os.path.getsize(file_path)
```

Then we loop (possibly) through all file entries to find a new empty file entry. A new empty file entry can be recognised by a NULL-byte string of length 32:

```json
  if name == b"\x00" * 32:
                free_entry_new = next_entry_offset
```

If a new empty file entry is found it is stored. Otherwise the function returns a message, stating that there is no free entry left.

Then for the last part the function updates every necessary field, which includes:

- the starting point of the file content
- the file size
- the padding
- the timestamp, which is calculated via the time.time() function
- the second padding

Finally, the function reads in the content of the given file:

```json
  file_bytes = file_to_add.read()
```

And plugs it in at the evaluated appending point. After the file insertion the required metadata is adjusted and updated.

The function terminates with the following message, if everything has worked out:

```json
  f"Successfully added file {filename} to the filesystem {fsname}"
```

### def do_getfs(fsname, filename):

Extract a file from the virtual filesystem and write it back to the host filesystem.

Locates the entry for the file in the filesystem using the helper function search_file_in_fs_counter(fsname, filename).
It then uses that information to compute the start field of the File entry.
There we extract the informations we need to get the correct file. We then read that file, convert it to a string with utf-8 and then write and return that file.

### def do_rmfs(fsname, filename):

Marks a file entry as deleted and updates the corresponding filesystem metadata.
Searches for the the file with our helper function search_file_in_fs_counter(fsname, filename).
It then opens the filesystem in "r+b" allowing to also write in the binary format.
Save the current filecount, to be able to decrease it.
We also need to mark the file itself as deleted. Since the counter is the number of the fileentry we need to seek that many entry-sizes after the header to enter the correct fileentry. There we need to go to the correct field at byte 41.
Then we also update the deleted_files field in the header.

Then the Function returns a message, that the file is marked as deleted.

### def do_lsfs(fsname):

Lists all active files (meaning non-deleted ones) together with their size and their created timestamp. After the regular checks the filesystem is opened. It iterates over the maximum amount of file entries (i.e 32) and checks the name of every file entry. If it is a NULL-byte and/or the field "deleted" equals 1 the entry is skipped. For all the active file entries, the name, size and timestamp are collected and added to a list. Finally the list is returned.

### def do_dfrgfs(fsname):

This function first does the regular checks. It then opens the file in read and write format and unpacks all fields from the header. Finally, we enter a for loop, to loop over all entries in the filesystem:

```json
    for i in range(MAX_FILES):
```

Inside each loop we unpack the fields of a file entry and check the name of the file. If the name is equal to the NULL-bytes it means it is not "occupied" and thus we skip it. Otherwise, we check if the flag is equal to 1, which means a file marked as deleted and skip them as well. We then create a dictionary for all "non-deleted" files and append important information about these files to a list, that finally contains all files, that have to remain:

```json
    active_entries = []
    # some code here
    if flag == 1:
        deleted_count += 1

    # now the deleted files are gone, and we append the active ones.

    active_entries.append(
                {
                    "name": name_raw,
                    "length": length,
                    "type": ftype,
                    "created": created,
                    "data": data,
                }
```

We then clear the file entry table.
Then, we write all file entries again together with the file content. Additionally the header is updated.
Finally, the function cuts the end of the newly inserted data bytes so that old data is removed entirely:

```json
    fsys.truncate(new_next_free)
```

### def do_catfs(fsname, filename):

First the function does the regular checks of existency of the filesystem as well as the file, whose content should be printed to the command line.
Afterwards, the file gets opened in "read-binary" mode and searches for the file inside the filesystem. When found, the file entry within the file entries is seeked and the name, the starting position as well as the length of the file are evaluated. Finally, the functions seeks the correct position in the data region, unpacks the data according to the given length and returns the content.

## Step 02

When implementing the same filesystem in Java, we tried to stick to our Python solution as best as possible, concerning labelling, design architecture and logic. As Java was completely new to us, the solution is more of a "made-up" solution, that has been checked and improved from day to day. The source used to implement the Java solution are stated below.

### Helper Functions

#### public void assert_header_layout()

This helper function (that gets called by another helper function public void layout_assertions()) asserts that the basic constants regarding the header layout, that were defined before actually meet the requirements from the assignment and are correct.

#### public void assert_file_entry()

This helper function (that gets called by another helper function public void layout_assertions()) asserts that the basic constants in for the file-entry, that were defined before actually meet the requirements from the assignment and are correct.

#### public void seek(ByteArrayInputStream input, int position)

This tiny helper function allows to "jump" to a desired position in a ByteArrayInputStream. This is very usefule to read or write specific content into the filesystem we use.

#### public void layout_assertions()

Calls the helper functions asser_header_layout() and assert_file_entry() to make sure, the layout constants are still the same and don't produce a mess. Should only really be needed if the constants/ offsets get changed.

### protected String mkfs(String fsName)

As this was the first of our Java methods, it was important to come up with a proper layout that would path the way for all of our further methods. One big difficulty in the beginning was to find all the types for the data fields (short, int, etc.) but with the use of the cited sources below, a nice solution was found. Furthermore, it was difficult to find a nice and easy way to write to files. For this a very helpful solution online was found, that is cited in the sources section as well. Apart from that, the mkfs() function was tried to be implemented as close as possible to the python solution.

### protected String gifs(String fsName) throws IOException

In the implementation of this function I did not have one big problem but more many small little challenges.
Since the implementation's logic and design is very close to the python's one, the difficulties happened mostly when translating it to java.
Even though these components didn't require much effort, translating it to java -a language unfamiliar to me (Elia)- was not that easy.
So through all was a little uncertanity if I'm using correct java and if this really works in java.
Since the logic is pretty near at the python one, I managed to orient myself hardly at the python implementation.
One specific java challenge was to read/ get the correct types, since I'm not used doing that.

### protected String addfs(String fsName, String fileName) throws IOException

The implementation is very close to our python implementation from the design and logic point of view.
However, there were some difficulties that arose during the process: First of all, it was difficult to come up with a proper solution of how to read the current header and file entries efficiently.
We decided to stick to an implementation where we load in the header as well as the file entries into byte buffers, a they are both of fixed size and this implementation makes accessing the respective fields fast and simple.
Secondly, Noël really struggled to get the syntax of the module operation (for the padding right).
More on that can be seen in the subsection about the use of Generative AI.
Another big problem was for sure to get to the right place and change/ put data there.
Once we knew about .position and .put and knew how to use those functions, it wasn't a big problem anymore, but getting there was a bit challenging.

While working with (binary-)Files was not that big of a problem in python with the open function, it was not as trivial doing it in java.
With some classical research we stepped upon the RandomAccessFile Constructor which behaves pretty similar in our case.
Also a problem seemed to be to only write the needed information and bytes.
How it turned it out, it wasn't really a big problem but still an uncertainity that made it difficult to just translate it to java.

### protected String getfs(String fsName, String fileName) throws IOException

The implementation is quite similar to our python implementation regarding the design and especially logic components.
Even though these components didn't require much effort, translating it to java -a language unfamiliar to me was not that easy.

The biggest challenge was how to find, read and especially write the needed file out. For the process of searching I used the same structure we did for rmfs.
The hardest part was to understand how you can correctly read and write the file-data in java.
Creating the search part with the counter was also tricky, but most of the effort already happened before and not in this specific function.
While we call in the python file a helper function for searching a file, we search directly in the function here.

How to use the byte-type and correctly seek and read the file was difficult, since I was not used to java-functions and not 100% sure if it really does what I want.
How to write the file out, was also a little challenge, but since FileOutputStream is pretty common in java I was able to find out and do that without a great struggle.
The biggest Problem was actually a little logical error we implemented in the fileentry which led to the restored files having additional bytes in them.
Since I'm not yet comfortable with debugging java and correctly implement was also a java-specific challenge.

### protected String rmfs(String fsName, String fileName) throws IOException

In Python, `rmfs` was very straight forward: find the entry, flip the flag, done. In Java we do the same, but more low-level. We first call `layout_assertions()` to be sure all offsets are still correct, then read header and entries into `ByteBuffer`s. To find the file we have to decode the 32-byte name field to a `String` and strip the `\0` bytes every time, which is more verbose than in Python. Updating `file_count` and `deleted_files` also made us think about `short` being signed and not mixing up types. The logic is the same as in Python, but Java forces us to be explicit about each byte.

### protected String lsfs(String fsName) throws IOException

`lsfs` follows the same idea as the Python version: loop over all entries, skip empty or deleted ones, and print name, size and timestamp. In Java we had to explicitly test whether the 32-byte name array is all zeros to detect an empty slot, instead of one simple comparison as in Python. Building the output string with `StringBuilder` is also more mechanical than using f-strings. Here we really saw how sensitive the code is to correct offsets like `ENTRY_LENGTH_OFFSET` and `ENTRY_CREATED_OFFSET`, so the `assert_file_entry()` helper actually helped us avoid subtle bugs.

### protected String dfrgfs(String fsName) throws IOException

`dfrgfs` is where the Java port was the hardest. In Python we could use lists of dicts and slices on a `bytes` object; in Java we had to introduce an `EntryMeta` class, two buffers (`data` and `newData`), and keep track of absolute vs. relative offsets by hand. Re-implementing the 64-byte alignment (`((offset + 63) // 64) * 64` in Python) with `ALIGNMENT` and integers took some care. We used `gifs` and `lsfs` after each change to check if `file_count`, `deleted_files`, and `next_free_offset` still made sense. Overall, this method showed most clearly how much more boilerplate we need in Java for the same algorithm.

### protected String catfs(String fsName, String fileName) throws IOException

For `catfs` the main decision was: return raw bytes or a `String`. We chose a UTF-8 `String`, because all assignment examples use text files and the Python version also prints text. The search part is the same pattern as in `getfs` and `rmfs`: scan entries, decode the 32-byte name, strip `\0`, check the flag and compare. This repetition made us notice how much easier it is in Python to hide this in a helper function; in Java we kept it inline so the struct layout remains visible in the code.

## Showcase of the Solution

### Python Implementation

Here we present a showcase of our Python implementation based on our solution.

```json
Call: python zvfs.py mkfs filesystem1.zvfs
Output: Created new Virtual Filesystem called filesystem1.zvfs.
```

We first create the following two .txt files:

```json
echo Hello, world! > test_file1.txt
echo The weather is nice today > test_file2.txt
```

```json
Call: python zvfs.py addfs filesystem1.zvfs test_file1.txt
Call 2: python zvfs.py addfs filesystem1.zvfs test_file2.txt
Output: Successfully added file test_file1.txt to the filesystem filesystem1.zvfs
Output 2: Successfully added file test_file2.txt to the filesystem filesystem1.zvfs
```

```json
Call: python zvfs.py lsfs filesystem1.zvfs
Output: [('test_file1.txt', '14 Bytes', 1765294886), ('test_file2.txt', '26 Bytes', 1765294892)]
```

```json
Call: python zvfs.py catfs filesystem1.zvfs test_file1.txt
Output: Hello, world!
```

We first deleted the test_file1.txt file from our disk and then try to restore it from the filesystem

```json
Call: python zvfs.py getfs filesystem1.zvfs test_file1.txt
Output: Returned file test_file1.txt from filesystem1.zvfs to Disk
```

```json
Call: python zvfs.py gifs filesystem1.zvfs
Output: The filesystem1.zvfs filesystem has got 2 active entries, 30 free entries and 0 are marked as deleted. The total size of the filesystem is 2240 Bytes.
```

```json
Call: python zvfs.py rmfs filesystem1.zvfs test_file1.txt
Output: File test_file1.txt marked as deleted in filesystem filesystem1.zvfs. Filesystem filesystem1.zvfs now has 1 files marked as deleted.
```

```json
Call: python zvfs.py gifs filesystem1.zvfs
Output: The filesystem1.zvfs filesystem has got 1 active entries, 30 free entries and 1 are marked as deleted. The total size of the filesystem is 2240 Bytes.
```

```json
Call: python zvfs.py lsfs filesystem1.zvfs
Output: [('test_file2.txt', '26 Bytes', 1765101842)]
```

```json
Call: python zvfs.py dfrgfs filesystem1.zvfs
Output: Defragmented 1 files and freed 64 bytes of file data.
```

```json
Call: python zvfs.py gifs filesystem1.zvfs
Output: The filesystem1.zvfs filesystem has got 1 active entries, 31 free entries and 0 are marked as deleted. The total size of the filesystem is 2176 Bytes.
```

```json
Call: python zvfs.py lsfs filesystem1.zvfs
Output: [('test_file2.txt', '26 Bytes', 1765101842)]
```

### Java Implementation

Here we present a showcase of our Java implementation based on our solution.

```json
Call: java zvfs mkfs filesystem2.zvfs
Output: Created new filesystem
```

We used the same two .txt files as in the python showcase

```json
Call: java zvfs addfs filesystem2.zvfs test_file1.txt
Output: Added file test_file1.txt to filesystem filesystem2.zvfs
```

```json
Call: java zvfs addfs filesystem2.zvfs test_file2.txt
Output: Added file test_file2.txt to filesystem filesystem2.zvfs
```

```json
Call: java zvfs lsfs filesystem2.zvfs
Output: test_file1.txt  14 Bytes  created=1765212344
        test_file2.txt  26 Bytes  created=1765212381
```

```json
Call: java zvfs catfs filesystem2.zvfs test_file1.txt
Output: Hello, world!
```

Then the file test_file1.txt was removed from the disk

```json
Call: java zvfs getfs filesystem2.zvfs test_file1.txt
Output: Returned file test_file1.txt from filesystem filesystem2.zvfs to disk.
```

```json
Call: java zvfs gifs filesystem2.zvfs
Output: File System: filesystem2.zvfs
        Number of active files: 2
        Number of deleted files: 0
        Free entries for new files: 30
        Total size of the file: 2240 bytes
```

```json
Call: java zvfs rmfs filesystem2.zvfs test_file1.txt
Output: File test_file1.txt marked as deleted in filesystem filesystem2.zvfs
```

```json
Call: java zvfs gifs filesystem2.zvfs
Output: File System: filesystem2.zvfs
        Number of active files: 1
        Number of deleted files: 1
        Free entries for new files: 30
        Total size of the file: 2240 bytes
```

```json
Call: java zvfs lsfs filesystem2.zvfs
Output: test_file2.txt  26 Bytes  created=1765212381
```

```json
Call: java zvfs dfrgfs filesystem2.zvfs
Output: Defragmented 1 files and freed 64 bytes of file data.
```

```json
Call: java zvfs gifs filesystem2.zvfs
Output: File System: filesystem2.zvfs
        Number of active files: 1
        Number of deleted files: 0
        Free entries for new files: 31
        Total size of the file: 2176 bytes
```

```json
Call: java zvfs lsfs filesystem2.zvfs
Output: test_file2.txt  26 Bytes  created=1765212381
```

### Cross-language Showcase

In this last chapter of the showcase part we aim to show the functionality of our solution across the two implementation languages. Thus, we present some examplary use cases where we call functions in one programming language on the filesystem created by the other programming language:

Currently both filesystem only have test_file2.txt present.

```json
Call: java zvfs lsfs filesystem1.zvfs
Output: test_file2.txt  26 Bytes  created=1765294892
```

```json
Call: python zvfs.py lsfs filesystem2.zvfs
Output: [('test_file2.txt', '26 Bytes', 1765295291)]
```

This showcase shows, that listing the present files works across the programming languages

```json
Call: java zvfs gifs filesystem1.zvfs
Output: File System: filesystem1.zvfs
        Number of active files: 1
        Number of deleted files: 0
        Free entries for new files: 31
        Total size of the file: 2176 bytes
```

```json
Call: python zvfs.py gifs filesystem2.zvfs
Output: The filesystem2.zvfs filesystem has got 1 active entries, 31 free entries and 0 are marked as deleted. The total size of the filesystem is 2176 Bytes.
```

Now, let's first remove the test_file2.txt from the Disk and restore it out of the java-created filesystem using the python function and vice versa.

```json
Call: java zvfs getfs filesystem1.zvfs test_file2.txt
Output: Returned file test_file2.txt from filesystem filesystem1.zvfs to disk.
```

```json
Call: python zvfs.py getfs filesystem2.zvfs test_file2.txt
Output: Returned file test_file2.txt from filesystem2.zvfs to Disk
```

Additionally, let's also print some content of test_file2.txt out of the java-created filesystem using the python function and vice versa.

```json
Call: java zvfs catfs filesystem1.zvfs test_file2.txt
Output: The weather is nice today
```

```json
Call: python zvfs.py catfs filesystem2.zvfs test_file2.txt
Output: The weather is nice today
```

Finally, we delete and defragment the files from both of our filesystem implementation using the opposite language's function to validate cross-language functionality.

```json
Call: java zvfs rmfs filesystem1.zvfs test_file2.txt
Output: File test_file2.txt marked as deleted in filesystem filesystem1.zvfs
```

```json
Call: java zvfs dfrgfs filesystem1.zvfs
Output: Defragmented 1 files and freed 64 bytes of file data.
```

```json
Call: python zvfs.py rmfs filesystem2.zvfs test_file2.txt
Output: File test_file2.txt marked as deleted in filesystem filesystem2.zvfs. Filesystem filesystem2.zvfs now has 1 files marked as deleted.
```

```json
Call: python zvfs.py dfrgfs filesystem2.zvfs
Output: Defragmented 1 files and freed 64 bytes of file data.
```

To prove that the files have been removed:

```json
Call: java zvfs gifs filesystem1.zvfs
Output: File System: filesystem1.zvfs
        Number of active files: 0
        Number of deleted files: 0
        Free entries for new files: 32
        Total size of the file: 2112 bytes
```

```json
Call: python zvfs.py gifs filesystem2.zvfs
Output: The filesystem2.zvfs filesystem has got 0 active entries, 32 free entries and 0 are marked as deleted. The total size of the filesystem is 2112 Bytes.
```

These numberous examples demonstrate the cross-language functionality of our filesystems implemented in both Java and Python.

# Use of Generative AI

## For the Java Implementation

### Noël

I used it for the following purpose

#### Writing to a File in Java

I found the FileOutputStream on geeksforgeeks (see source below), however I did not get, why I need to specify header.array() when writing to a fos. Hence, I asked ChatGPT using the following prompt:

"in this line of code: fos.write(header.array())

if header is a ByteBuffer, why do we need to specify header.array() and not just header when writing to fos?"

Response:

FileOutputStream.write(...) does not know how to write a ByteBuffer.
It only accepts raw bytes, not buffer objects.

Here is the key point:

FileOutputStream.write() expects:
• byte[]
• int
• byte[] with offset/length

A ByteBuffer is not a byte[]

It is an object that contains bytes, but the bytes are stored inside an internal array.

Therefore you must extract the underlying byte array.

#### Using Modulo Operation in Java

I somehow did not get how to use the modulo operation in Java. I struggled to declare it and always got an error. I prompted the following:

"Why does this always give an error: int padding = ((64 - (dataSize % 64)) % 64);"

Response:

"Because dataSize is a long, and the % operator between a long and an int produces a long.
But you are trying to store the result into an int.

The real issue

dataSize % 64 is of type long."

So I had to adapt: int padding = (int)((64 - (dataSize % 64)) % 64);

### Enrique

I used ChatGPT mainly as a tutor to help me derive and name all the header- and layout-related constants for both the Python and Java implementations based on the tables in the assignment PDF. The tool suggested a consistent set of names and offsets, which I then checked against the specification and integrated into our own code.
Representative prompt I used:

> “Here is the header table from the assignment (offsets, field names, sizes). First, add all the constants from this table to make the `# ----- Constants -----` section in `zvfs.py` complete. Write them as Python constants with clear comments so I can paste them at the top of `zvfs.py`.”
> A similar prompt was used for Java:
> “We already have the full list of constants in Python. Translate this whole constants block into Java so I can put it at the top of `FileSystem.java`, using `final int` fields and the same names and comments.”

# Sources

## For the Python Implementation

- For format in packing: https://www.geeksforgeeks.org/python/struct-pack-in-python/
- For format in packing: https://docs.micropython.org/en/latest/library/struct.html
- Getting size of a file: https://www.digitalocean.com/community/tutorials/how-to-get-file-size-in-python

## For the Java Implementation

- Format for the ByteBuffer: https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html
- Writing Binary to File: https://www.geeksforgeeks.org/java/fileoutputstream-in-java/
- To seek to a Byte Position in Binary Array: https://stackoverflow.com/questions/3792747/seeking-a-bytearrayinputstream-using-java-io
- To Decode Bytes to String: https://labex.io/tutorials/java-how-to-decode-bytes-to-string-421749
- To read Binary File to ByteBuffer: https://stackoverflow.com/questions/7250229/reading-a-binary-input-stream-into-a-single-byte-array-in-java
- To write and edit in file: https://www.baeldung.com/java-write-to-file

```

```
