/*
 * Copyright (c) 2011-2019 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package io.vertx.tests.http.fileupload;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.InterfaceHttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.MemoryAttribute;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.NetSocket;
import io.vertx.tests.http.MultipartDecoderTestSupport;
import io.vertx.test.http.HttpConfig;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 */
public class Http1xServerFileUploadTest extends HttpServerFileUploadTest {

  public Http1xServerFileUploadTest() {
    super(HttpConfig.Http1x.DEFAULT);
  }

  @Test
  public void testAbortedMultipartUploadCleansDecoderAfterNotifications() throws Exception {
    AtomicBoolean uploadExceptionSeen = new AtomicBoolean();
    String boundary = "vertx-boundary";
    server.requestHandler(req -> {
      req.setExpectMultipart(true);
      req.uploadHandler(upload -> upload.exceptionHandler(err -> {
        uploadExceptionSeen.set(true);
        Assert.assertNotNull(MultipartDecoderTestSupport.fieldValue(req, "decoder"));
      }));
      req.exceptionHandler(err -> {
        Assert.assertTrue(uploadExceptionSeen.get());
        Assert.assertNotNull(MultipartDecoderTestSupport.fieldValue(req, "decoder"));
        MultipartDecoderTestSupport.assertFieldClearedNextTick(req, "decoder", () -> {
          testComplete();
        });
      });
      req.endHandler(v -> Assert.fail("Should not end on aborted multipart upload"));
    });
    startServer(testAddress);

    NetSocket socket = vertx.createNetClient().connect(testAddress).await();
    socket.write("POST /form HTTP/1.1\r\n" +
      "Host: localhost\r\n" +
      "Content-Type: " + MultipartDecoderTestSupport.multipartContentType(boundary) + "\r\n" +
      "Content-Length: 512\r\n" +
      "\r\n").await();
    socket.write(MultipartDecoderTestSupport.partialMultipartBody(boundary)).await();
    socket.close().await();

    await();
  }

  @Test
  public void testMalformedMultipartFinalizationDoesNotDoubleCleanupDecoder() throws Exception {
    AtomicReference<FailingHttpPostRequestDecoder> failingDecoder = new AtomicReference<>();
    server.requestHandler(req -> {
      req.setExpectMultipart(true);
      FailingHttpPostRequestDecoder decoder = new FailingHttpPostRequestDecoder();
      failingDecoder.set(decoder);
      MultipartDecoderTestSupport.setFieldValue(req, "decoder", decoder);
      req.exceptionHandler(err -> {
        Assert.assertNotNull(MultipartDecoderTestSupport.fieldValue(req, "decoder"));
        MultipartDecoderTestSupport.assertFieldClearedNextTick(req, "decoder", () -> {
          Assert.assertEquals(1, failingDecoder.get().destroyCount);
          testComplete();
        });
      });
    });
    startServer(testAddress);

    NetSocket socket = vertx.createNetClient().connect(testAddress).await();
    socket.write("POST /form HTTP/1.1\r\n" +
      "Host: localhost\r\n" +
      "Content-Type: " + MultipartDecoderTestSupport.multipartContentType("vertx-boundary") + "\r\n" +
      "Content-Length: 0\r\n" +
      "\r\n").await();

    await();
    socket.close().await();
  }

  @Test
  public void testMultipartAttributeFinalizationFailureDoesNotReenterDecoderLoop() throws Exception {
    AtomicReference<AttributeFailingHttpPostRequestDecoder> failingDecoder = new AtomicReference<>();
    server.requestHandler(req -> {
      req.setExpectMultipart(true);
      AttributeFailingHttpPostRequestDecoder decoder = new AttributeFailingHttpPostRequestDecoder();
      failingDecoder.set(decoder);
      MultipartDecoderTestSupport.setFieldValue(req, "decoder", decoder);
      req.exceptionHandler(err -> {
        Assert.assertNotNull(MultipartDecoderTestSupport.fieldValue(req, "decoder"));
        MultipartDecoderTestSupport.assertFieldClearedNextTick(req, "decoder", () -> {
          Assert.assertEquals(1, failingDecoder.get().destroyCount);
          testComplete();
        });
      });
    });
    startServer(testAddress);

    NetSocket socket = vertx.createNetClient().connect(testAddress).await();
    socket.write("POST /form HTTP/1.1\r\n" +
      "Host: localhost\r\n" +
      "Content-Type: " + MultipartDecoderTestSupport.multipartContentType("vertx-boundary") + "\r\n" +
      "Content-Length: 0\r\n" +
      "\r\n").await();

    await();
    socket.close().await();
  }

  @Ignore
  @Test
  @Override
  public void testBrokenFormUploadLargeFile() {
    super.testBrokenFormUploadLargeFile();
  }

  @Ignore
  @Test
  @Override
  public void testBrokenFormUploadLargeFileStreamToDisk() {
    super.testBrokenFormUploadLargeFileStreamToDisk();
  }

  private static final class FailingHttpPostRequestDecoder extends HttpPostRequestDecoder {

    int destroyCount;

    private FailingHttpPostRequestDecoder() {
      super(new DefaultHttpRequest(io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
        io.netty.handler.codec.http.HttpMethod.POST, "/"));
    }

    @Override
    public InterfaceHttpPostRequestDecoder offer(HttpContent content) {
      if (content == LastHttpContent.EMPTY_LAST_CONTENT) {
        throw new ErrorDataDecoderException("Synthetic endDecode failure");
      }
      return this;
    }

    @Override
    public boolean hasNext() {
      return false;
    }

    @Override
    public InterfaceHttpData next() {
      throw new EndOfDataDecoderException();
    }

    @Override
    public InterfaceHttpData currentPartialHttpData() {
      return null;
    }

    @Override
    public void destroy() {
      destroyCount++;
    }
  }

  private static final class AttributeFailingHttpPostRequestDecoder extends HttpPostRequestDecoder {

    int destroyCount;
    boolean returned;

    private AttributeFailingHttpPostRequestDecoder() {
      super(new DefaultHttpRequest(io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
        io.netty.handler.codec.http.HttpMethod.POST, "/"));
    }

    @Override
    public InterfaceHttpPostRequestDecoder offer(HttpContent content) {
      return this;
    }

    @Override
    public boolean hasNext() {
      return !returned;
    }

    @Override
    public InterfaceHttpData next() {
      returned = true;
      return new FailingAttribute("boom");
    }

    @Override
    public InterfaceHttpData currentPartialHttpData() {
      return null;
    }

    @Override
    public void destroy() {
      destroyCount++;
    }
  }

  private static final class FailingAttribute extends MemoryAttribute {

    private FailingAttribute(String name) {
      super(name);
    }

    @Override
    public String getValue() {
      throw new IllegalStateException("Synthetic attribute finalization failure");
    }
  }

}
