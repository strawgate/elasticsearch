/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.scalar.ip;

import com.carrotsearch.randomizedtesting.annotations.Name;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.elasticsearch.common.network.InetAddresses;
import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.AbstractScalarFunctionTestCase;
import org.elasticsearch.xpack.esql.expression.function.TestCaseSupplier;
import org.elasticsearch.xpack.esql.type.EsqlDataTypeConverter;

import java.util.List;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.equalTo;

public class IpPrefixTests extends AbstractScalarFunctionTestCase {

    /**
     * Tests that buffer reuse across multiple invocations doesn't cause stale data.
     * This tests the bug where when remainingBits == 0, the byte at fullBytes index
     * was not being zeroed, leading to stale data from previous invocations.
     */
    public void testBufferReuseWithDifferentPrefixLengths() {
        // Create a shared scratch buffer (simulates THREAD_LOCAL reuse)
        var scratch = new org.apache.lucene.util.BytesRef(new byte[16], 0, 16);

        // First call: IP with /32 prefix (fullBytes=4, remainingBits=0)
        // This will set bytes 0-3 to the IP octets and bytes 4-15 should be zeroed
        var ip1 = EsqlDataTypeConverter.stringToIP("192.168.1.255");
        var result1 = IpPrefix.process(scratch, ip1, 32, 128);
        assertThat(result1, equalTo(EsqlDataTypeConverter.stringToIP("192.168.1.255")));

        // Second call: IP with /24 prefix (fullBytes=3, remainingBits=0)
        // This will set bytes 0-2 to the IP octets
        // The bug was that byte 3 wasn't zeroed, keeping the "255" from the previous call
        var ip2 = EsqlDataTypeConverter.stringToIP("10.0.0.1");
        var result2 = IpPrefix.process(scratch, ip2, 24, 128);

        // Without the fix, this would incorrectly include the "255" from ip1
        // resulting in "10.0.0.255" instead of the correct "10.0.0.0"
        assertThat(result2, equalTo(EsqlDataTypeConverter.stringToIP("10.0.0.0")));

        // Third call: Verify with another /16 prefix
        var ip3 = EsqlDataTypeConverter.stringToIP("172.31.255.254");
        var result3 = IpPrefix.process(scratch, ip3, 16, 128);
        assertThat(result3, equalTo(EsqlDataTypeConverter.stringToIP("172.31.0.0")));
    }
    public IpPrefixTests(@Name("TestCase") Supplier<TestCaseSupplier.TestCase> testCaseSupplier) {
        this.testCase = testCaseSupplier.get();
    }

    @ParametersFactory
    public static Iterable<Object[]> parameters() {
        var suppliers = List.of(
            // V4
            new TestCaseSupplier(
                List.of(DataType.IP, DataType.INTEGER, DataType.INTEGER),
                () -> new TestCaseSupplier.TestCase(
                    List.of(
                        new TestCaseSupplier.TypedData(EsqlDataTypeConverter.stringToIP("1.2.3.4"), DataType.IP, "ip"),
                        new TestCaseSupplier.TypedData(24, DataType.INTEGER, "prefixLengthV4"),
                        new TestCaseSupplier.TypedData(ESTestCase.randomIntBetween(0, 128), DataType.INTEGER, "prefixLengthV6")
                    ),
                    "IpPrefixEvaluator[ip=Attribute[channel=0], prefixLengthV4=Attribute[channel=1], prefixLengthV6=Attribute[channel=2]]",
                    DataType.IP,
                    equalTo(EsqlDataTypeConverter.stringToIP("1.2.3.0"))
                )
            ),
            new TestCaseSupplier(List.of(DataType.IP, DataType.INTEGER, DataType.INTEGER), () -> {
                var randomIp = randomIp(true);
                var randomPrefix = randomIntBetween(0, 32);
                var cidrString = InetAddresses.toCidrString(randomIp, randomPrefix);

                var ipParameter = EsqlDataTypeConverter.stringToIP(NetworkAddress.format(randomIp));
                var expectedPrefix = EsqlDataTypeConverter.stringToIP(
                    NetworkAddress.format(InetAddresses.parseIpRangeFromCidr(cidrString).lowerBound())
                );

                return new TestCaseSupplier.TestCase(
                    List.of(
                        new TestCaseSupplier.TypedData(ipParameter, DataType.IP, "ip"),
                        new TestCaseSupplier.TypedData(randomPrefix, DataType.INTEGER, "prefixLengthV4"),
                        new TestCaseSupplier.TypedData(ESTestCase.randomIntBetween(0, 128), DataType.INTEGER, "prefixLengthV6")
                    ),
                    "IpPrefixEvaluator[ip=Attribute[channel=0], prefixLengthV4=Attribute[channel=1], prefixLengthV6=Attribute[channel=2]]",
                    DataType.IP,
                    equalTo(expectedPrefix)
                );
            }),

            // V6
            new TestCaseSupplier(
                List.of(DataType.IP, DataType.INTEGER, DataType.INTEGER),
                () -> new TestCaseSupplier.TestCase(
                    List.of(
                        new TestCaseSupplier.TypedData(EsqlDataTypeConverter.stringToIP("::ff"), DataType.IP, "ip"),
                        new TestCaseSupplier.TypedData(ESTestCase.randomIntBetween(0, 32), DataType.INTEGER, "prefixLengthV4"),
                        new TestCaseSupplier.TypedData(127, DataType.INTEGER, "prefixLengthV6")
                    ),
                    "IpPrefixEvaluator[ip=Attribute[channel=0], prefixLengthV4=Attribute[channel=1], prefixLengthV6=Attribute[channel=2]]",
                    DataType.IP,
                    equalTo(EsqlDataTypeConverter.stringToIP("::fe"))
                )
            ),
            new TestCaseSupplier(List.of(DataType.IP, DataType.INTEGER, DataType.INTEGER), () -> {
                var randomIp = randomIp(false);
                var randomPrefix = randomIntBetween(0, 128);
                var cidrString = InetAddresses.toCidrString(randomIp, randomPrefix);

                var ipParameter = EsqlDataTypeConverter.stringToIP(NetworkAddress.format(randomIp));
                var expectedPrefix = EsqlDataTypeConverter.stringToIP(
                    NetworkAddress.format(InetAddresses.parseIpRangeFromCidr(cidrString).lowerBound())
                );

                return new TestCaseSupplier.TestCase(
                    List.of(
                        new TestCaseSupplier.TypedData(ipParameter, DataType.IP, "ip"),
                        new TestCaseSupplier.TypedData(ESTestCase.randomIntBetween(0, 32), DataType.INTEGER, "prefixLengthV4"),
                        new TestCaseSupplier.TypedData(randomPrefix, DataType.INTEGER, "prefixLengthV6")
                    ),
                    "IpPrefixEvaluator[ip=Attribute[channel=0], prefixLengthV4=Attribute[channel=1], prefixLengthV6=Attribute[channel=2]]",
                    DataType.IP,
                    equalTo(expectedPrefix)
                );
            })
        );

        return parameterSuppliersFromTypedDataWithDefaultChecks(true, suppliers);
    }

    @Override
    protected Expression build(Source source, List<Expression> args) {
        return new IpPrefix(source, args.get(0), args.get(1), args.size() == 3 ? args.get(2) : null);
    }
}
