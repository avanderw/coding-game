/*
 * Copyright 2016 Igor Maznitsa.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.igormaznitsa.jcp.expression.functions;

import com.igormaznitsa.jcp.context.PreprocessorContext;
import com.igormaznitsa.jcp.expression.Value;
import com.igormaznitsa.jcp.expression.ValueType;

import java.io.*;

import java.util.Locale;
import java.util.zip.Deflater;
import javax.annotation.Nonnull;

import javax.annotation.Nullable;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;

import com.igormaznitsa.meta.annotation.MustNotContainNull;

/**
 * The Function loads bin file and encodes it into string.
 *
 * @author Igor Maznitsa (http://www.igormaznitsa.com)
 * @since 6.1.0
 */
public class FunctionBINFILE extends AbstractFunction {

  private static final ValueType[][] ARG_TYPES = new ValueType[][]{{ValueType.STRING, ValueType.STRING}};

  private enum Type {
    BASE64("base64"),
    BYTEARRAY("byte[]"),
    UINT8("uint8[]"),
    INT8("int8[]");

    private final String name;

    private Type(@Nonnull final String name) {
      this.name = name;
    }

    @Nonnull
    public String getName() {
      return this.name;
    }

    @Nullable
    public static Type find(@Nullable final String name) {
      Type result = null;
      if (name != null) {
        final String normalized = name.toLowerCase(Locale.ENGLISH).trim();
        for (final Type t : values()) {
          if (normalized.startsWith(t.name)) {
            result = t;
            break;
          }
        }
      }
      return result;
    }
  }

  private static boolean hasSplitFlag(@Nonnull final String name, @Nonnull final Type type) {
    final String opts = name.substring(type.name.length());
    return opts.contains("S") || opts.contains("s");
  }

  private static boolean hasDeflateFlag(@Nonnull final String name, @Nonnull final Type type) {
    final String opts = name.substring(type.name.length());
    return opts.contains("D") || opts.contains("d");
  }

  @Override
  @Nonnull
  public String getName() {
    return "binfile";
  }

  @Override
  @Nonnull
  public String getReference() {
    final StringBuilder buffer = new StringBuilder();
    for (final Type t : Type.values()) {
      if (buffer.length() > 0) {
        buffer.append('|');
      }
      buffer.append(t.name);
    }
    buffer.append("[s|d|sd|ds]");
    return "encode bin file into string representation, allowed types [" + buffer.toString() + "], s - split to lines, d - deflater compression";
  }

  @Override
  public int getArity() {
    return 2;
  }

  @Override
  @Nonnull
  @MustNotContainNull
  public ValueType[][] getAllowedArgumentTypes() {
    return ARG_TYPES;
  }

  @Override
  @Nonnull
  public ValueType getResultType() {
    return ValueType.STRING;
  }

  @Nonnull
  public Value executeStrStr(@Nonnull final PreprocessorContext context, @Nonnull final Value strfilePath, @Nonnull final Value encodeType) {
    final String filePath = strfilePath.asString();
    final String encodeTypeAsString = encodeType.asString();
    final Type type = Type.find(encodeTypeAsString);

    if (type == null) {
      throw context.makeException("Unsupported encode type [" + encodeType.asString() + ']', null);
    }

    final int lengthOfLine  = hasSplitFlag(encodeTypeAsString, type) ? 80 : -1;
    final boolean doDeflate  = hasDeflateFlag(encodeTypeAsString, type);
    
    final File theFile;
    try {
      theFile = context.findFileInSourceFolder(filePath);
    }
    catch (IOException ex) {
      throw context.makeException("Can't find bin file '" + filePath + '\'', null);
    }

    if (context.isVerbose()) {
      context.logForVerbose("Loading content of bin file '" + theFile + '\'');
    }

    try {
      final String endOfLine = System.getProperty("line.separator", "\r\n");
      return Value.valueOf(convertTo(theFile, type, doDeflate, lengthOfLine, endOfLine));
    }
    catch (Exception ex) {
      throw context.makeException("Unexpected exception", ex);
    }
  }

  @Nonnull
  private static String convertTo(@Nonnull final File file, @Nonnull final Type type, final boolean deflate, final int lineLength, @Nonnull final String endOfLine) throws IOException {
    final StringBuilder result = new StringBuilder(512);
    byte[] array = FileUtils.readFileToByteArray(file);

    if (deflate) {
      array = deflate(array);
    }

    int endLinePos = lineLength;
    boolean addNextLine = false;

    switch (type) {
      case BASE64: {
        final String baseEncoded = new Base64(lineLength, endOfLine.getBytes("UTF-8"), false).encodeAsString(array);
        result.append(baseEncoded.trim());
      }
      break;
      case BYTEARRAY:
      case INT8:
      case UINT8: {
        for (final byte b : array) {
          if (result.length() > 0) {
            result.append(',');
          }

          if (addNextLine) {
            addNextLine = false;
            result.append(endOfLine);
          }

          switch (type) {
            case BYTEARRAY: {
              result.append("(byte)0x").append(Integer.toHexString(b & 0xFF).toUpperCase(Locale.ENGLISH));
            }
            break;
            case UINT8: {
              result.append(Integer.toString(b & 0xFF).toUpperCase(Locale.ENGLISH));
            }
            break;
            case INT8: {
              result.append(Integer.toString(b).toUpperCase(Locale.ENGLISH));
            }
            break;
            default:
              throw new Error("Unexpected type : " + type);
          }

          if (lineLength > 0 && result.length() >= endLinePos) {
            addNextLine = true;
            endLinePos = result.length() + lineLength;
          }
        }

      }
      break;
      default:
        throw new Error("Unexpected type : " + type);
    }

    return result.toString();
  }

  @Nonnull
  private static byte[] deflate(@Nonnull final byte[] data) throws IOException {
    final Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
    deflater.setInput(data);

    final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length);

    deflater.finish();
    final byte[] buffer = new byte[1024];
    while (!deflater.finished()) {
      final int count = deflater.deflate(buffer);
      outputStream.write(buffer, 0, count);
    }
    outputStream.close();
    final byte[] output = outputStream.toByteArray();

    deflater.end();

    return output;
  }
}
