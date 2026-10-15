package dev.bgame.lanplus.relay;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

final class MinecraftReject {

    private MinecraftReject() {
    }

    static void send(Socket player, int nextState, int protocol, String descriptionJson, String reasonJson)
            throws IOException {
        InputStream in = player.getInputStream();
        OutputStream out = player.getOutputStream();
        if (nextState == 1) {
            sendStatus(in, out, protocol, descriptionJson);
        } else if (nextState == 2) {
            sendLoginDisconnect(out, reasonJson);
        }
    }

    private static void sendStatus(InputStream in, OutputStream out, int protocol, String descriptionJson)
            throws IOException {
        readPacket(in);
        String json = "{\"version\":{\"name\":\"LAN+\",\"protocol\":" + protocol
                + "},\"players\":{\"max\":0,\"online\":0},\"description\":" + descriptionJson + "}";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, 0x00);
        writeString(body, json);
        writePacket(out, body.toByteArray());
        out.flush();
        byte[] ping = readPacket(in);
        if (ping != null) {
            writePacket(out, ping);
            out.flush();
        }
    }

    private static void sendLoginDisconnect(OutputStream out, String reasonJson) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, 0x00);
        writeString(body, reasonJson);
        writePacket(out, body.toByteArray());
        out.flush();
    }

    private static void writePacket(OutputStream out, byte[] body) throws IOException {
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        writeVarInt(framed, body.length);
        framed.write(body, 0, body.length);
        out.write(framed.toByteArray());
    }

    private static byte[] readPacket(InputStream in) throws IOException {
        int len = readVarInt(in);
        if (len <= 0 || len > 32 * 1024) {
            return null;
        }
        byte[] data = in.readNBytes(len);
        return data.length == len ? data : null;
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b, 0, b.length);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        int pos = 0;
        int b;
        do {
            b = in.read();
            if (b == -1) {
                throw new EOFException("VarInt");
            }
            value |= (b & 0x7F) << pos;
            pos += 7;
            if (pos > 35) {
                throw new IOException("VarInt too big");
            }
        } while ((b & 0x80) != 0);
        return value;
    }
}