package com.example.pdfmarkdown;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class OnnxModelDictionaryTest {
    @TempDir Path directory;
    @Test void readsCharactersAfterSupplementaryUnicodeWithoutTruncation() throws Exception {
        String dictionary="中\nA\n𠮷\n😀\nZ";
        var entry=new ByteArrayOutputStream();field(entry,10,"character");field(entry,18,dictionary);
        var model=new ByteArrayOutputStream();model.write(8);model.write(9);model.write(114);writeVarint(model,entry.size());model.write(entry.toByteArray());
        Path path=directory.resolve("sample.onnx");Files.write(path,model.toByteArray());
        assertThat(OnnxModelDictionary.read(path)).isEqualTo(dictionary);
    }
    @Test void rejectsTruncatedMetadataInsteadOfSilentlyMisaligningDictionary() throws Exception {
        Path path=directory.resolve("bad.onnx");Files.write(path,new byte[]{114,10,10,2,65});
        assertThatThrownBy(()->OnnxModelDictionary.read(path)).isInstanceOf(IOException.class);
    }
    @Test void modelWithoutEmbeddedDictionaryReturnsNull() throws Exception {
        Path path=directory.resolve("empty.onnx");Files.write(path,new byte[]{8,9});
        assertThat(OnnxModelDictionary.read(path)).isNull();
    }
    private static void field(ByteArrayOutputStream out,int tag,String text) throws IOException {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);out.write(tag);writeVarint(out,bytes.length);out.write(bytes);
    }
    private static void writeVarint(ByteArrayOutputStream out,int value){while(value>127){out.write((value&127)|128);value>>>=7;}out.write(value);}
}
