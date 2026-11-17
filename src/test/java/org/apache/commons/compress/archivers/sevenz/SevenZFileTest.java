/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.commons.compress.archivers.sevenz;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;

import javax.crypto.Cipher;

import org.apache.commons.compress.AbstractTestCase;
import org.apache.commons.compress.PasswordRequiredException;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.Test;

public class SevenZFileTest extends AbstractTestCase {
    private static final String TEST2_CONTENT = "<?xml version = '1.0'?>\r\n<!DOCTYPE"
        + " connections>\r\n<meinxml>\r\n\t<leer />\r\n</meinxml>\n";

    // https://issues.apache.org/jira/browse/COMPRESS-320
    @Test
    public void testRandomlySkippingEntries() throws Exception {
        // Read sequential reference.
        final Map<String, byte[]> entriesByName = new HashMap<>();
        SevenZFile archive = new SevenZFile(getFile("COMPRESS-320/Copy.7z"));
        SevenZArchiveEntry entry;
        while ((entry = archive.getNextEntry()) != null) {
            if (entry.hasStream()) {
                entriesByName.put(entry.getName(), readFully(archive));
            }
        }
        archive.close();

        final String[] variants = {
            "BZip2-solid.7z",
            "BZip2.7z",
            "Copy-solid.7z",
            "Copy.7z",
            "Deflate-solid.7z",
            "Deflate.7z",
            "LZMA-solid.7z",
            "LZMA.7z",
            "LZMA2-solid.7z",
            "LZMA2.7z",
            // TODO: unsupported compression method.
            // "PPMd-solid.7z",
            // "PPMd.7z",
        };

        // TODO: use randomizedtesting for predictable, but different, randomness.
        final Random rnd = new Random(0xdeadbeef);
        for (final String fileName : variants) {
            archive = new SevenZFile(getFile("COMPRESS-320/" + fileName));

            while ((entry = archive.getNextEntry()) != null) {
                // Sometimes skip reading entries.
                if (rnd.nextBoolean()) {
                    continue;
                }

                if (entry.hasStream()) {
                    assertTrue(entriesByName.containsKey(entry.getName()));
                    final byte [] content = readFully(archive);
                    assertTrue("Content mismatch on: " + fileName + "!" + entry.getName(),
                               Arrays.equals(content, entriesByName.get(entry.getName())));
                }
            }

            archive.close();
        }
    }

    private byte[] readFully(final SevenZFile archive) throws IOException {
        final byte [] buf = new byte [1024];
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (int len = 0; (len = archive.read(buf)) > 0;) {
            baos.write(buf, 0, len);
        }
        return baos.toByteArray();
    }

    @Test
    public void testAllEmptyFilesArchive() throws Exception {
        try (SevenZFile archive = new SevenZFile(getFile("7z-empty-mhc-off.7z"))) {
            assertNotNull(archive.getNextEntry());
        }
    }

    @Test
    public void testHelloWorldHeaderCompressionOffCopy() throws Exception {
        checkHelloWorld("7z-hello-mhc-off-copy.7z");
    }

    @Test
    public void testHelloWorldHeaderCompressionOffLZMA2() throws Exception {
        checkHelloWorld("7z-hello-mhc-off-lzma2.7z");
    }

    @Test
    public void test7zUnarchive() throws Exception {
        test7zUnarchive(getFile("bla.7z"), SevenZMethod.LZMA);
    }

    @Test
    public void test7zDeflateUnarchive() throws Exception {
        test7zUnarchive(getFile("bla.deflate.7z"), SevenZMethod.DEFLATE);
    }

    @Test
    public void test7zDeflate64Unarchive() throws Exception {
        test7zUnarchive(getFile("bla.deflate64.7z"), SevenZMethod.DEFLATE64);
    }

    @Test
    public void test7zDecryptUnarchive() throws Exception {
        if (isStrongCryptoAvailable()) {
            test7zUnarchive(getFile("bla.encrypted.7z"), SevenZMethod.LZMA, // stack LZMA + AES
                            "foo".getBytes("UTF-16LE"));
        }
    }

    @Test
    public void test7zDecryptUnarchiveUsingCharArrayPassword() throws Exception {
        if (isStrongCryptoAvailable()) {
            test7zUnarchive(getFile("bla.encrypted.7z"), SevenZMethod.LZMA, // stack LZMA + AES
                            "foo".toCharArray());
        }
    }

    private void test7zUnarchive(final File f, final SevenZMethod m) throws Exception {
        test7zUnarchive(f, m, (char[]) null);
    }

    @Test
    public void testEncryptedArchiveRequiresPassword() throws Exception {
        try {
            new SevenZFile(getFile("bla.encrypted.7z")).close();
            fail("shouldn't decrypt without a password");
        } catch (final PasswordRequiredException ex) {
            final String msg = ex.getMessage();
            assertTrue("Should start with whining about being unable to decrypt",
                       msg.startsWith("Cannot read encrypted content from "));
            assertTrue("Should finish the sentence properly",
                       msg.endsWith(" without a password."));
            assertTrue("Should contain archive's name",
                       msg.contains("bla.encrypted.7z"));
        }
    }

    /**
     * @see "https://issues.apache.org/jira/browse/COMPRESS-256"
     */
    @Test
    public void testCompressedHeaderWithNonDefaultDictionarySize() throws Exception {
        try (SevenZFile sevenZFile = new SevenZFile(getFile("COMPRESS-256.7z"))) {
            int count = 0;
            while (sevenZFile.getNextEntry() != null) {
                count++;
            }
            assertEquals(446, count);
        }
    }

    @Test
    public void testSignatureCheck() {
        assertTrue(SevenZFile.matches(SevenZFile.sevenZSignature,
                                      SevenZFile.sevenZSignature.length));
        assertTrue(SevenZFile.matches(SevenZFile.sevenZSignature,
                                      SevenZFile.sevenZSignature.length + 1));
        assertFalse(SevenZFile.matches(SevenZFile.sevenZSignature,
                                      SevenZFile.sevenZSignature.length - 1));
        assertFalse(SevenZFile.matches(new byte[] { 1, 2, 3, 4, 5, 6 }, 6));
        assertTrue(SevenZFile.matches(new byte[] { '7', 'z', (byte) 0xBC,
                                                   (byte) 0xAF, 0x27, 0x1C}, 6));
        assertFalse(SevenZFile.matches(new byte[] { '7', 'z', (byte) 0xBC,
                                                    (byte) 0xAF, 0x27, 0x1D}, 6));
    }

    @Test
    public void testReadingBackLZMA2DictSize() throws Exception {
        final File output = new File(dir, "lzma2-dictsize.7z");
        try (SevenZOutputFile outArchive = new SevenZOutputFile(output)) {
            outArchive.setContentMethods(Arrays.asList(new SevenZMethodConfiguration(SevenZMethod.LZMA2, 1 << 20)));
            final SevenZArchiveEntry entry = new SevenZArchiveEntry();
            entry.setName("foo.txt");
            outArchive.putArchiveEntry(entry);
            outArchive.write(new byte[] { 'A' });
            outArchive.closeArchiveEntry();
        }

        try (SevenZFile archive = new SevenZFile(output)) {
            final SevenZArchiveEntry entry = archive.getNextEntry();
            final SevenZMethodConfiguration m = entry.getContentMethods().iterator().next();
            assertEquals(SevenZMethod.LZMA2, m.getMethod());
            assertEquals(1 << 20, m.getOptions());
        }
    }

    @Test
    public void testReadingBackDeltaDistance() throws Exception {
        final File output = new File(dir, "delta-distance.7z");
        try (SevenZOutputFile outArchive = new SevenZOutputFile(output)) {
            outArchive.setContentMethods(Arrays.asList(new SevenZMethodConfiguration(SevenZMethod.DELTA_FILTER, 32),
                    new SevenZMethodConfiguration(SevenZMethod.LZMA2)));
            final SevenZArchiveEntry entry = new SevenZArchiveEntry();
            entry.setName("foo.txt");
            outArchive.putArchiveEntry(entry);
            outArchive.write(new byte[] { 'A' });
            outArchive.closeArchiveEntry();
        }

        try (SevenZFile archive = new SevenZFile(output)) {
            final SevenZArchiveEntry entry = archive.getNextEntry();
            final SevenZMethodConfiguration m = entry.getContentMethods().iterator().next();
            assertEquals(SevenZMethod.DELTA_FILTER, m.getMethod());
            assertEquals(32, m.getOptions());
        }
    }

    @Test
    public void getEntriesOfUnarchiveTest() throws IOException {
        try (SevenZFile sevenZFile = new SevenZFile(getFile("bla.7z"))) {
            final Iterable<SevenZArchiveEntry> entries = sevenZFile.getEntries();
            final Iterator<SevenZArchiveEntry> iter = entries.iterator();
            SevenZArchiveEntry entry = iter.next();
            assertEquals("test1.xml", entry.getName());
            entry = iter.next();
            assertEquals("test2.xml", entry.getName());
            assertFalse(iter.hasNext());
        }
    }

    @Test
    public void getEntriesOfUnarchiveInMemoryTest() throws IOException {
        byte[] data = null;
        try (FileInputStream fis = new FileInputStream(getFile("bla.7z"))) {
            data = IOUtils.toByteArray(fis);
        }
        try (SevenZFile sevenZFile = new SevenZFile(new SeekableInMemoryByteChannel(data))) {
            final Iterable<SevenZArchiveEntry> entries = sevenZFile.getEntries();
            final Iterator<SevenZArchiveEntry> iter = entries.iterator();
            SevenZArchiveEntry entry = iter.next();
            assertEquals("test1.xml", entry.getName());
            entry = iter.next();
            assertEquals("test2.xml", entry.getName());
            assertFalse(iter.hasNext());
        }
    }

    /**
     * @see "https://issues.apache.org/jira/browse/COMPRESS-348"
     */
    @Test
    public void readEntriesOfSize0() throws IOException {
        try (SevenZFile sevenZFile = new SevenZFile(getFile("COMPRESS-348.7z"))) {
            int entries = 0;
            SevenZArchiveEntry entry = sevenZFile.getNextEntry();
            while (entry != null) {
                entries++;
                final int b = sevenZFile.read();
                if ("2.txt".equals(entry.getName()) || "5.txt".equals(entry.getName())) {
                    assertEquals(-1, b);
                } else {
                    assertNotEquals(-1, b);
                }
                entry = sevenZFile.getNextEntry();
            }
            assertEquals(5, entries);
        }
    }

    private void test7zUnarchive(final File f, final SevenZMethod m, final byte[] password) throws Exception {
        try (SevenZFile sevenZFile = new SevenZFile(f, password)) {
            test7zUnarchive(sevenZFile, m);
        }
    }

    private void test7zUnarchive(final File f, final SevenZMethod m, final char[] password) throws Exception {
        try (SevenZFile sevenZFile = new SevenZFile(f, password)) {
            test7zUnarchive(sevenZFile, m);
        }
    }

    private void test7zUnarchive(SevenZFile sevenZFile, final SevenZMethod m) throws Exception {
        SevenZArchiveEntry entry = sevenZFile.getNextEntry();
        assertEquals("test1.xml", entry.getName());
        assertEquals(m, entry.getContentMethods().iterator().next().getMethod());
        entry = sevenZFile.getNextEntry();
        assertEquals("test2.xml", entry.getName());
        assertEquals(m, entry.getContentMethods().iterator().next().getMethod());
        final byte[] contents = new byte[(int) entry.getSize()];
        int off = 0;
        while ((off < contents.length)) {
            final int bytesRead = sevenZFile.read(contents, off, contents.length - off);
            assert (bytesRead >= 0);
            off += bytesRead;
        }
        assertEquals(TEST2_CONTENT, new String(contents, "UTF-8"));
        assertNull(sevenZFile.getNextEntry());
    }

    private void checkHelloWorld(final String filename) throws Exception {
        try (SevenZFile sevenZFile = new SevenZFile(getFile(filename))) {
            final SevenZArchiveEntry entry = sevenZFile.getNextEntry();
            assertEquals("Hello world.txt", entry.getName());
            final byte[] contents = new byte[(int) entry.getSize()];
            int off = 0;
            while ((off < contents.length)) {
                final int bytesRead = sevenZFile.read(contents, off, contents.length - off);
                assert (bytesRead >= 0);
                off += bytesRead;
            }
            assertEquals("Hello, world!\n", new String(contents, "UTF-8"));
            assertNull(sevenZFile.getNextEntry());
        }
    }

    @Test
    public void rejectsNextHeaderSizeBeyondArchiveSize() throws Exception {
        final byte[] header = new byte[] { NID.kHeader, NID.kEnd };
        assertCorruptedArchiveRejected(create7zArchive(0, Integer.MAX_VALUE - 8, header),
            "nextHeaderSize is out of bounds");
    }

    @Test
    public void rejectsNextHeaderOffsetBeyondArchiveSize() throws Exception {
        final byte[] header = new byte[] { NID.kHeader, NID.kEnd };
        assertCorruptedArchiveRejected(create7zArchive(1L << 40, header.length, header),
            "nextHeaderOffset is out of bounds");
    }

    @Test
    public void rejectsHugeArchivePropertySize() throws Exception {
        final ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(NID.kHeader);
        header.write(NID.kArchiveProperties);
        header.write(0x18);
        writeUint64(header, Integer.MAX_VALUE);
        header.write(new byte[] { 0, 0, 0, 0 });
        assertCorruptedArchiveRejected(header.toByteArray(), "invalid property size");
    }

    @Test
    public void rejectsHugeNumberOfPackStreams() throws Exception {
        final ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(NID.kHeader);
        header.write(NID.kMainStreamsInfo);
        header.write(NID.kPackInfo);
        writeUint64(header, 0); // packPos
        writeUint64(header, Integer.MAX_VALUE); // numPackStreams
        header.write(NID.kSize);
        writeUint64(header, 0x100000); // packSize
        header.write(new byte[] { 0, 0, 0, 0 });
        assertCorruptedArchiveRejected(header.toByteArray(), "packSize (1048576) is out of range");
    }

    @Test
    public void rejectsHugeNumberOfFolders() throws Exception {
        final ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(NID.kHeader);
        header.write(NID.kMainStreamsInfo);
        header.write(NID.kUnpackInfo);
        header.write(NID.kFolder);
        writeUint64(header, Integer.MAX_VALUE); // numFolders
        header.write(0); // external
        writeUint64(header, 0); // numCoders of first folder
        header.write(new byte[] { 0, 0, 0, 0 });
        assertCorruptedArchiveRejected(header.toByteArray(), "Folder without coders");
    }

    @Test
    public void rejectsHugeNumberOfCoders() throws Exception {
        assertCorruptedArchiveRejected(folderWithCoderProperties(Integer.MAX_VALUE),
            "invalid propertiesSize in folder");
    }

    @Test
    public void rejectsHugeCoderPropertiesSize() throws Exception {
        assertCorruptedArchiveRejected(folderWithCoderProperties(1),
            "invalid propertiesSize in folder");
    }

    @Test
    public void rejectsHugeNumberOfBindPairs() throws Exception {
        final ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(NID.kHeader);
        header.write(NID.kMainStreamsInfo);
        header.write(NID.kUnpackInfo);
        header.write(NID.kFolder);
        writeUint64(header, 1); // numFolders
        header.write(0); // external
        writeUint64(header, 1); // numCoders
        header.write(0x11); // complex coder with an id of one byte
        header.write(0); // coder id
        writeUint64(header, 1); // numInStreams
        writeUint64(header, Integer.MAX_VALUE); // numOutStreams
        header.write(new byte[] { 0, 0, 0, 0 });
        assertCorruptedArchiveRejected(header.toByteArray(),
            "Total input streams can't be less than the number of bind pairs");
    }

    @Test
    public void rejectsHugeNumberOfFiles() throws Exception {
        final ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(NID.kHeader);
        header.write(NID.kFilesInfo);
        writeUint64(header, Integer.MAX_VALUE); // numFiles
        header.write(0); // end of properties
        header.write(NID.kEnd);
        assertCorruptedArchiveRejected(header.toByteArray(), "archive with entries but no folders");
    }

    @Test
    public void rejectsHugeFileNamesSize() throws Exception {
        final ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(NID.kHeader);
        header.write(NID.kFilesInfo);
        writeUint64(header, 1); // numFiles
        header.write(NID.kName);
        writeUint64(header, Integer.MAX_VALUE); // size of names property
        header.write(0); // external
        header.write(new byte[] { 'a', 0, 0, 0 });
        try (SevenZFile sevenZFile = new SevenZFile(
                create7zArchive(0, header.size(), header.toByteArray()))) {
            fail("Expected an EOFException");
        } catch (final EOFException expected) {
            // expected, the names are truncated
        }
    }

    private static byte[] folderWithCoderProperties(final int numCoders) throws IOException {
        final ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(NID.kHeader);
        header.write(NID.kMainStreamsInfo);
        header.write(NID.kUnpackInfo);
        header.write(NID.kFolder);
        writeUint64(header, 1); // numFolders
        header.write(0); // external
        writeUint64(header, numCoders);
        header.write(0x21); // simple coder with attributes and an id of one byte
        header.write(0); // coder id
        writeUint64(header, Integer.MAX_VALUE); // propertiesSize
        header.write(new byte[] { 0, 0, 0, 0 });
        return header.toByteArray();
    }

    private static void assertCorruptedArchiveRejected(final byte[] header, final String expectedMessage)
        throws IOException {
        assertCorruptedArchiveRejected(create7zArchive(0, header.length, header), expectedMessage);
    }

    private static void assertCorruptedArchiveRejected(final SeekableInMemoryByteChannel archive,
        final String expectedMessage) throws IOException {
        try (SevenZFile sevenZFile = new SevenZFile(archive)) {
            fail("Expected an IOException with message " + expectedMessage);
        } catch (final IOException ex) {
            assertEquals(expectedMessage, ex.getMessage());
        }
    }

    private static SeekableInMemoryByteChannel create7zArchive(final long nextHeaderOffset,
        final long nextHeaderSize, final byte[] header) {
        final CRC32 crc = new CRC32();
        crc.update(header);
        final ByteBuffer startHeader = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        startHeader.putLong(nextHeaderOffset);
        startHeader.putLong(nextHeaderSize);
        startHeader.putInt((int) crc.getValue());
        crc.reset();
        crc.update(startHeader.array());

        final ByteBuffer archive = ByteBuffer.allocate(SevenZFile.SIGNATURE_HEADER_SIZE + header.length)
            .order(ByteOrder.LITTLE_ENDIAN);
        archive.put(SevenZFile.sevenZSignature);
        archive.put((byte) 0);
        archive.put((byte) 4);
        archive.putInt((int) crc.getValue());
        archive.put(startHeader.array());
        archive.put(header);
        return new SeekableInMemoryByteChannel(archive.array());
    }

    private static void writeUint64(final ByteArrayOutputStream header, long value) {
        int firstByte = 0;
        int mask = 0x80;
        int i;
        for (i = 0; i < 8; i++) {
            if (value < ((1L << ( 7  * (i + 1))))) {
                firstByte |= (value >>> (8 * i));
                break;
            }
            firstByte |= mask;
            mask >>>= 1;
        }
        header.write(firstByte);
        for (; i > 0; i--) {
            header.write((int) (0xff & value));
            value >>>= 8;
        }
    }

    private static boolean isStrongCryptoAvailable() throws NoSuchAlgorithmException {
        return Cipher.getMaxAllowedKeyLength("AES/ECB/PKCS5Padding") >= 256;
    }
}
