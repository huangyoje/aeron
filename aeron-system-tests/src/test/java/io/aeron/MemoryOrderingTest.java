/*
 * Copyright 2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron;

import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.*;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.fail;

@RunWith(Parameterized.class)
public class MemoryOrderingTest
{
    @Parameterized.Parameters
    public static List<Object[]> data()
    {
        return Arrays.asList(new Object[20][0]);
    }

    public static final String CHANNEL = "aeron:udp?endpoint=localhost:54325";
    public static final int STREAM_ID = 1;
    public static final int FRAGMENT_COUNT_LIMIT = 256;
    public static final int MESSAGE_LENGTH = 96;
    public static final int NUM_MESSAGES = 1_000_000;
    public static final int BURST_LENGTH = 5;
    public static final int INTER_BURST_DURATION_NS = 10_000;

    final UnsafeBuffer srcBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(MESSAGE_LENGTH));

    volatile String failedMessage = null;

    @Ignore
    @Test(timeout = 10000)
    public void shouldReceiveMessagesInOrderWithFirstLongWordIntact() throws Exception
    {
        srcBuffer.setMemory(0, MESSAGE_LENGTH, (byte)7);
        final MediaDriver.Context ctx = new MediaDriver.Context();

        try (final MediaDriver ignore = MediaDriver.launch(ctx);
             final Aeron aeron = Aeron.connect();
             final Publication publication = aeron.addPublication(CHANNEL, STREAM_ID);
             final Subscription subscription = aeron.addSubscription(CHANNEL, STREAM_ID))
        {
            final BusySpinIdleStrategy idleStrategy = new BusySpinIdleStrategy();

            final Thread subscriberThread = new Thread(new Subscriber(subscription));
            subscriberThread.start();

            for (int i = 0; i < NUM_MESSAGES; i++)
            {
                if (null != failedMessage)
                {
                    fail(failedMessage);
                }

                srcBuffer.putLong(0, i);

                while (publication.offer(srcBuffer) < 0L)
                {
                    if (null != failedMessage)
                    {
                        fail(failedMessage);
                    }

                    idleStrategy.idle();
                }

                if (i % BURST_LENGTH == 0)
                {
                    final long timeout = System.nanoTime() + INTER_BURST_DURATION_NS;
                    long now;
                    do
                    {
                        now = System.nanoTime();
                    }
                    while (now < timeout);
                }
            }

            subscriberThread.join();
        }
        finally
        {
            ctx.deleteAeronDirectory();
        }
    }

    public class Subscriber implements Runnable, FragmentHandler
    {
        private final Subscription subscription;

        long previousValue = -1;
        int messageNum = 0;

        public Subscriber(final Subscription subscription)
        {
            this.subscription = subscription;
        }

        public void run()
        {
            final BusySpinIdleStrategy idleStrategy = new BusySpinIdleStrategy();

            while (messageNum < NUM_MESSAGES && null == failedMessage)
            {
                idleStrategy.idle(subscription.poll(this, FRAGMENT_COUNT_LIMIT));
            }
        }

        public void onFragment(final DirectBuffer buffer, final int offset, final int length, final Header header)
        {
            final long messageValue = buffer.getLong(offset);

            final long expectedValue = previousValue + 1;
            if (messageValue != expectedValue)
            {
                final long messageValueSecondRead = buffer.getLong(offset);

                final String msg = "Issue at message number transition: " + previousValue + " -> " + messageValue;

                System.out.println(msg + "\n" +
                    "offset: "  + offset + "\n" +
                    "length: "  + length + "\n" +
                    "expected bytes: " + byteString(expectedValue) + "\n" +
                    "received bytes: " + byteString(messageValue) + "\n" +
                    "expected bits: " + Long.toBinaryString(expectedValue) + "\n" +
                    "received bits: " + Long.toBinaryString(messageValue) + "\n" +
                    "messageValue on second read: " + messageValueSecondRead + "\n" +
                    "messageValue on third read: " + buffer.getLong(offset));

                failedMessage = msg;
            }

            previousValue = messageValue;
            messageNum++;
        }

        private String byteString(final long value)
        {
            return String.format("%x %x %x %x %x %x %x %x",
                (byte)(value >>> 56),
                (byte)(value >>> 48),
                (byte)(value >>> 40),
                (byte)(value >>> 32),
                (byte)(value >>> 24),
                (byte)(value >>> 18),
                (byte)(value >>> 8),
                (byte)value);
        }
    }
}