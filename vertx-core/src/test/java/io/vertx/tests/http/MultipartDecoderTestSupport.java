/*
 * Copyright (c) 2011-2026 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package io.vertx.tests.http;

import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import org.junit.Assert;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class MultipartDecoderTestSupport {

  private static final Object UNSAFE = unsafe();
  private static final Method OBJECT_FIELD_OFFSET = unsafeMethod("objectFieldOffset", Field.class);
  private static final Method GET_OBJECT = unsafeMethod("getObject", Object.class, long.class);
  private static final Method PUT_OBJECT = unsafeMethod("putObject", Object.class, long.class, Object.class);

  private MultipartDecoderTestSupport() {
  }

  public static Object fieldValue(Object target, String fieldName) {
    try {
      Field field = declaredField(target.getClass(), fieldName);
      long offset = (long) OBJECT_FIELD_OFFSET.invoke(UNSAFE, field);
      return GET_OBJECT.invoke(UNSAFE, target, offset);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  public static void setFieldValue(Object target, String fieldName, Object value) {
    try {
      Field field = declaredField(target.getClass(), fieldName);
      long offset = (long) OBJECT_FIELD_OFFSET.invoke(UNSAFE, field);
      PUT_OBJECT.invoke(UNSAFE, target, offset, value);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  public static String multipartContentType(String boundary) {
    return "multipart/form-data; boundary=" + boundary;
  }

  public static String partialMultipartBody(String boundary) {
    return "--" + boundary + "\r\n" +
      "Content-Disposition: form-data; name=\"file\"; filename=\"tmp-0.txt\"\r\n" +
      "Content-Type: text/plain\r\n" +
      "\r\n" +
      "partial-content";
  }

  public static String invalidMultipartBody(String boundary) {
    return "--" + boundary + "\r\n" +
      "Content-Disposition: form-data; name=\"file\"; filename=\"tmp-0.txt\"\r\n" +
      "Content-Type: image/gif; charset=ABCD\r\n" +
      "Content-Length: 12\r\n" +
      "\r\n" +
      "some-content\r\n" +
      "--" + boundary + "--\r\n";
  }

  public static void assertFieldClearedNextTick(Object target, String fieldName, Runnable afterClear) {
    ContextInternal context = (ContextInternal) Vertx.currentContext();
    Assert.assertNotNull(context);
    context.runOnContext(v -> {
      Assert.assertNull(fieldValue(target, fieldName));
      if (afterClear != null) {
        afterClear.run();
      }
    });
  }

  private static Field declaredField(Class<?> type, String fieldName) throws NoSuchFieldException {
    Class<?> current = type;
    while (current != null) {
      try {
        return current.getDeclaredField(fieldName);
      } catch (NoSuchFieldException ignore) {
        current = current.getSuperclass();
      }
    }
    throw new NoSuchFieldException(fieldName);
  }

  private static Object unsafe() {
    try {
      Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
      Field field = unsafeClass.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      return field.get(null);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static Method unsafeMethod(String name, Class<?>... parameterTypes) {
    try {
      return UNSAFE.getClass().getMethod(name, parameterTypes);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
