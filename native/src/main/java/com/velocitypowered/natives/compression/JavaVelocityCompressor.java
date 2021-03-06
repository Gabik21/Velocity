package com.velocitypowered.natives.compression;

import static com.velocitypowered.natives.compression.CompressorUtils.ZLIB_BUFFER_SIZE;
import static com.velocitypowered.natives.compression.CompressorUtils.ensureMaxSize;

import com.google.common.base.Preconditions;
import com.velocitypowered.natives.util.BufferPreference;
import io.netty.buffer.ByteBuf;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class JavaVelocityCompressor implements VelocityCompressor {

  public static final VelocityCompressorFactory FACTORY = JavaVelocityCompressor::new;

  private final Deflater deflater;
  private final Inflater inflater;
  private byte[] buf = new byte[0];
  private boolean disposed = false;

  private JavaVelocityCompressor(int level) {
    this.deflater = new Deflater(level);
    this.inflater = new Inflater();
  }

  @Override
  public void inflate(ByteBuf source, ByteBuf destination, int max) throws DataFormatException {
    ensureNotDisposed();

    final int available = source.readableBytes();
    this.setInflaterInput(source);

    if (destination.hasArray()) {
      this.inflateDestinationIsHeap(destination, available, max);
    } else {
      if (buf.length == 0) {
        buf = new byte[ZLIB_BUFFER_SIZE];
      }
      while (!inflater.finished() && inflater.getBytesRead() < available) {
        ensureMaxSize(destination, max);
        int read = inflater.inflate(buf);
        destination.writeBytes(buf, 0, read);
      }
    }
    inflater.reset();
  }

  private void setInflaterInput(ByteBuf source) {
    final int available = source.readableBytes();
    if (source.hasArray()) {
      inflater.setInput(source.array(), source.arrayOffset() + source.readerIndex(), available);
    } else {
      byte[] inData = new byte[available];
      source.readBytes(inData);
      inflater.setInput(inData);
    }
  }

  private void inflateDestinationIsHeap(ByteBuf destination, int available, int max)
      throws DataFormatException {
    while (!inflater.finished() && inflater.getBytesRead() < available) {
      if (!destination.isWritable()) {
        ensureMaxSize(destination, max);
        destination.ensureWritable(ZLIB_BUFFER_SIZE);
      }

      ensureMaxSize(destination, max);
      int produced = inflater.inflate(destination.array(), destination.arrayOffset()
          + destination.writerIndex(), destination.writableBytes());
      destination.writerIndex(destination.writerIndex() + produced);
    }
  }

  @Override
  public void deflate(ByteBuf source, ByteBuf destination) throws DataFormatException {
    ensureNotDisposed();

    this.setDeflaterInput(source);
    deflater.finish();

    if (destination.hasArray()) {
      this.deflateDestinationIsHeap(destination);
    } else {
      if (buf.length == 0) {
        buf = new byte[ZLIB_BUFFER_SIZE];
      }
      while (!deflater.finished()) {
        int bytes = deflater.deflate(buf);
        destination.writeBytes(buf, 0, bytes);
      }
    }
    deflater.reset();
  }

  private void setDeflaterInput(ByteBuf source) {
    if (source.hasArray()) {
      deflater.setInput(source.array(), source.arrayOffset() + source.readerIndex(),
          source.readableBytes());
    } else {
      byte[] inData = new byte[source.readableBytes()];
      source.readBytes(inData);
      deflater.setInput(inData);
    }
  }

  private void deflateDestinationIsHeap(ByteBuf destination) {
    while (!deflater.finished()) {
      if (!destination.isWritable()) {
        destination.ensureWritable(ZLIB_BUFFER_SIZE);
      }

      int produced = deflater.deflate(destination.array(), destination.arrayOffset()
          + destination.writerIndex(), destination.writableBytes());
      destination.writerIndex(destination.writerIndex() + produced);
    }
  }

  @Override
  public void dispose() {
    disposed = true;
    deflater.end();
    inflater.end();
  }

  private void ensureNotDisposed() {
    Preconditions.checkState(!disposed, "Object already disposed");
  }

  @Override
  public BufferPreference preferredBufferType() {
    return BufferPreference.HEAP_PREFERRED;
  }
}
