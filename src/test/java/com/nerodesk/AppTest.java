/**
 * Copyright (c) 2015, nerodesk.com
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met: 1) Redistributions of source code must retain the above
 * copyright notice, this list of conditions and the following
 * disclaimer. 2) Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided
 * with the distribution. 3) Neither the name of the nerodesk.com nor
 * the names of its contributors may be used to endorse or promote
 * products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT
 * NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.nerodesk;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.jcabi.http.Request;
import com.jcabi.http.request.JdkRequest;
import com.jcabi.http.response.RestResponse;
import com.jcabi.http.response.XmlResponse;
import com.jcabi.http.wire.VerboseWire;
import com.nerodesk.om.Base;
import com.nerodesk.om.mock.MkBase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHeaders;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.Ignore;
import org.junit.Test;
import org.takes.http.FtRemote;

/**
 * Test case for {@code Launch}.
 *
 * @author Yegor Bugayenko (yegor@teamed.io)
 * @version $Id$
 * @since 0.2
 * @checkstyle ClassDataAbstractionCouplingCheck (500 lines)
 * @todo #14:15min Application should be able to get binary file properly.
 *  Add a test to check it works and fix if doesn't.
 * @todo #89:1h Doc should support partitioned read.
 *  If file is too big to fit in one read request it should be split
 *  by the Doc on parts and returned to the client one-by-one.
 *  It will use multipart/form-data requests with Content-Range header.
 *  See http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.16
 *  for details about Content-Range.
 *  Client side should will receive first response, read Content-Range
 *  and request for the subsequent partitions.
 *  AWS S3 already supports partitioned read operation
 *  (see GetObjectRequest.setRange() for details). For the MkDoc partitioned
 *  read should be implemented as well.
 *  Let's start from proper tests. See example for partitioned write
 *  AppTest.uploadsBigFile()
 */
@SuppressWarnings("PMD.TooManyMethods")
public final class AppTest {

    /**
     * Fake URN.
     */
    private static final String FAKE_URN = "urn:test:1";

    /**
     * Launches web server on random port.
     * @throws Exception If fails
     */
    @Test
    public void launchesOnRandomPort() throws Exception {
        final App app = new App(new MkBase());
        new FtRemote(app).exec(
            new FtRemote.Script() {
                @Override
                public void exec(final URI home) throws IOException {
                    new JdkRequest(home)
                        .fetch()
                        .as(RestResponse.class)
                        .assertStatus(HttpURLConnection.HTTP_OK)
                        .as(XmlResponse.class)
                        .assertXPath("/xhtml:html");
                    new JdkRequest(home)
                        .through(VerboseWire.class)
                        .header("Accept", "application/xml")
                        .fetch()
                        .as(RestResponse.class)
                        .assertStatus(HttpURLConnection.HTTP_OK)
                        .as(XmlResponse.class)
                        .assertXPath("/page/version");
                }
            }
        );
    }

    /**
     * Application can return file content.
     * @throws Exception If fails
     */
    @Test
    public void returnsFileContent() throws Exception {
        final Base base = new MkBase();
        final String name = "test.txt";
        base.user(AppTest.FAKE_URN).docs().doc(name).write(
            new ByteArrayInputStream("hello, world!".getBytes())
        );
        final App app = new App(base);
        new FtRemote(app).exec(
            new FtRemote.Script() {
                @Override
                public void exec(final URI home) throws IOException {
                    new JdkRequest(home)
                        .uri().path("/r").queryParam("f", name).back()
                        .fetch()
                        .as(RestResponse.class)
                        .assertStatus(HttpURLConnection.HTTP_OK)
                        .assertBody(Matchers.startsWith("hello, world"));
                }
            }
        );
    }

    /**
     * Application can upload file content.
     * @throws Exception If fails
     */
    @Test
    public void uploadsFileContent() throws Exception {
        final Base base = new MkBase();
        final String name = "small.txt";
        final String file = "uploaded by client";
        new FtRemote(new App(base)).exec(
            new FtRemote.Script() {
                @Override
                public void exec(final URI home) throws IOException {
                    AppTest.write(home)
                        .fetch(
                            new ByteArrayInputStream(
                                AppTest.multipart(name, file).getBytes()
                            )
                        )
                        .as(RestResponse.class)
                        .assertStatus(HttpURLConnection.HTTP_SEE_OTHER);
                }
            }
        );
        final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        base.user(AppTest.FAKE_URN).docs().doc(name).read(stream);
        MatcherAssert.assertThat(
            IOUtils.toString(stream.toByteArray(), Charsets.UTF_8.name()),
            Matchers.containsString(file)
        );
    }

    /**
     * Application can upload big file split on parts.
     * @todo #89:1h Doc should support partitioned write.
     *  If file is too big to fit in one write request it will be split
     *  by the client side on parts and sent in several requests.
     *  It will use multipart/form-data requests with Content-Range header.
     *  See http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.16
     *  for details about Content-Range.
     *  Server side should receive these requests, retrieve and store
     *  file parts on-the-fly. We should not wait for the whole file.
     *  AWS S3 already supports partitioned operations
     *  (see AmazonS3.uploadPart() for details). For the MkDoc partitioned
     *  write should be implemented as well. Unignore the test after
     *  MkDoc partitioned write is implemented.
     * @throws Exception If fails
     */
    @Test
    @Ignore
    public void uploadsBigFile() throws Exception {
        final int psize = 5;
        final Base base = new MkBase();
        final String name = "large.txt";
        final String file = "123451234512345";
        new FtRemote(new App(base)).exec(
            // @checkstyle AnonInnerLengthCheck (30 lines)
            new FtRemote.Script() {
                @Override
                public void exec(final URI home) throws IOException {
                    int pos = 0;
                    while (pos < file.length() - 1) {
                        AppTest.write(home)
                            .header(
                                HttpHeaders.CONTENT_RANGE,
                                String.format(
                                    "bytes %d-%d/%d",
                                    pos, pos + psize, file.length()
                                )
                            )
                            .fetch(
                                new ByteArrayInputStream(
                                    AppTest.multipart(name, file).getBytes()
                                )
                            )
                            .as(RestResponse.class)
                            .assertStatus(HttpURLConnection.HTTP_OK);
                        pos += psize;
                    }
                }
            }
        );
        final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        base.user(AppTest.FAKE_URN).docs().doc(name).read(stream);
        MatcherAssert.assertThat(
            IOUtils.toString(stream.toByteArray(), Charsets.UTF_8.name()),
            Matchers.containsString(file)
        );
    }

    /**
     * Application can show error page.
     * @throws Exception If fails
     */
    @Test
    public void showsErrorPage() throws Exception {
        final Base base = new MkBase();
        final App app = new App(base);
        new FtRemote(app).exec(
            new FtRemote.Script() {
                @Override
                public void exec(final URI home) throws IOException {
                    new JdkRequest(home)
                        .uri().path("/d").back()
                        .fetch()
                        .as(RestResponse.class)
                        .assertStatus(HttpURLConnection.HTTP_OK)
                        .assertBody(
                            Matchers.allOf(
                                Matchers.startsWith(
                                    "oops, something went wrong!"
                            ),
                                Matchers.containsString(
                                    "java.util.NoSuchElementException"
                            )
                        )
                    );
                }
            }
        );
    }

    /**
     * Create request to add a file.
     * @param home URI home
     * @return Request
     */
    private static Request write(final URI home) {
        return new JdkRequest(home)
            .method("POST")
            .uri().path("/w").back()
            .header(
                HttpHeaders.CONTENT_TYPE,
                "multipart/form-data; boundary=AaB03x"
            );
    }

    /**
     * Multipart request body.
     * @param name File name
     * @param content File content
     * @return Request body
     */
    private static String multipart(final String name, final String content) {
        return Joiner.on("\r\n").join(
            " --AaB03x",
            "Content-Disposition: form-data; name=\"name\"",
            "",
            name,
            "--AaB03x",
            "Content-Disposition: form-data; name=\"file\"",
            "Content-Transfer-Encoding: utf-8",
            "",
            content,
            "--AaB03x--"
        );
    }

}
