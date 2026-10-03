package com.aifashionvault.platform.importer;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
/**
 * DNS + address safety gate for the share-import resolver. All A/AAAA records
 * of every hostname are resolved and every candidate must be externally
 * routable before any connection attempt. Literal IPv4/IPv6 authorities pass
 * through the same policy.
 */
public class ImportDns {

	private final Resolver resolver;

	public ImportDns() {
		this(hostname -> InetAddress.getAllByName(hostname));
	}

	ImportDns(Resolver resolver) {
		this.resolver = resolver;
	}

	/**
	 * Resolves the URI host and validates every candidate address.
	 *
	 * @throws ImportNetworkException DNS_FAILURE when lookup fails or yields no
	 * addresses; UNSAFE_URL when a candidate is not externally routable
	 */
	public ResolvedTarget resolveHost(URI httpUri) {
		String host = httpUri.getHost();
		if (host == null || host.isBlank()) {
			throw new ImportNetworkException(FailureKind.UNSAFE_URL, "uri requires a host");
		}
		InetAddress[] candidates;
		try {
			candidates = resolver.lookup(host);
		}
		catch (UnknownHostException ex) {
			throw new ImportNetworkException(FailureKind.DNS_FAILURE, "dns lookup failed", ex);
		}
		if (candidates == null || candidates.length == 0) {
			throw new ImportNetworkException(FailureKind.DNS_FAILURE, "dns returned no addresses");
		}
		for (InetAddress address : candidates) {
			if (address == null || !isExternallyRoutable(address)) {
				throw new ImportNetworkException(FailureKind.UNSAFE_URL, "hostname resolves to a blocked address");
			}
		}
		return new ResolvedTarget(host, candidates);
	}

	ResolvedTarget resolveHost(URI httpUri, long timeoutNanos) {
		if (timeoutNanos <= 0) {
			throw new ImportNetworkException(FailureKind.TIMEOUT, "dns budget exhausted");
		}
		CompletableFuture<ResolvedTarget> lookup = CompletableFuture.supplyAsync(() -> resolveHost(httpUri));
		try {
			return lookup.get(timeoutNanos, TimeUnit.NANOSECONDS);
		}
		catch (TimeoutException ex) {
			lookup.cancel(true);
			throw new ImportNetworkException(FailureKind.TIMEOUT, "dns lookup timed out", ex);
		}
		catch (ExecutionException ex) {
			if (ex.getCause() instanceof ImportNetworkException failure) {
				throw failure;
			}
			throw new ImportNetworkException(FailureKind.DNS_FAILURE, "dns lookup failed", ex.getCause());
		}
		catch (InterruptedException ex) {
			lookup.cancel(true);
			Thread.currentThread().interrupt();
			throw new ImportNetworkException(FailureKind.TIMEOUT, "dns lookup interrupted", ex);
		}
	}

	/** Policy check for a single literal address. */
	public void requireRoutable(InetAddress address) {
		if (address == null || !isExternallyRoutable(address)) {
			throw new ImportNetworkException(FailureKind.UNSAFE_URL, "address is not externally routable");
		}
	}

	/**
	 * True when the address is a plausible public unicast address: not loopback,
	 * private/site-local, link-local, multicast, unspecified, documentation,
	 * benchmark, CGNAT, IPv4-mapped private IPv6, or cloud metadata. Only
	 * IPv4/IPv6 are considered; anything else is blocked.
	 */
	public static boolean isExternallyRoutable(InetAddress address) {
		if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
				|| address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return false;
		}
		byte[] a = address.getAddress();
		if (address instanceof Inet6Address) {
			return isRoutableIpv6(a);
		}
		return a.length == 4 && isRoutableIpv4(a);
	}

	private static boolean isRoutableIpv6(byte[] a) {
		// fc00::/7 unique local addresses
		if ((a[0] & 0xFE) == 0xFC) {
			return false;
		}
		boolean leadingZeroBytes = true;
		for (int i = 0; i < 10; i++) {
			if (a[i] != 0) {
				leadingZeroBytes = false;
				break;
			}
		}
		if (leadingZeroBytes) {
			boolean ipv4Mapped = a[10] == (byte) 0xFF && a[11] == (byte) 0xFF;
			if (ipv4Mapped || (a[10] == 0 && a[11] == 0)) {
				// ::ffff:a.b.c.d and compatible ::a.b.c.d follow embedded IPv4 policy
				return isRoutableIpv4(new byte[] {a[12], a[13], a[14], a[15]});
			}
		}
		// 64:ff9b::/96 is IPv4-embedded NAT64; 64:ff9b:1::/48 is local-use translation.
		boolean wellKnownNat64 = a[0] == 0 && a[1] == 0x64 && a[2] == (byte) 0xFF
			&& a[3] == (byte) 0x9B && a[4] == 0 && a[5] == 0;
		if (wellKnownNat64 && allZero(a, 6, 11)) {
			return isRoutableIpv4(new byte[] {a[12], a[13], a[14], a[15]});
		}
		if (a[0] == 0 && a[1] == 0x64 && a[2] == (byte) 0xFF && a[3] == (byte) 0x9B && a[4] == 0 && a[5] == 1) {
			return false;
		}
		// 100::/64 discard-only, 2001::/23 IETF special-purpose, and 2001:db8::/32 documentation.
		if (a[0] == 1 && allZero(a, 1, 7)) {
			return false;
		}
		if (a[0] == 0x20 && a[1] == 1 && (a[2] & 0xFE) == 0) {
			return false;
		}
		if (a[0] == 0x20 && a[1] == 1 && a[2] == 0x0D && a[3] == (byte) 0xB8) {
			return false;
		}
		// 3fff::/20 documentation (RFC 9637).
		if (a[0] == 0x3F && (a[1] & 0xF0) == 0xF0) {
			return false;
		}
		// 2002::/16 embeds an IPv4 destination in the next four bytes.
		if (a[0] == 0x20 && a[1] == 2) {
			return isRoutableIpv4(new byte[] {a[2], a[3], a[4], a[5]});
		}
		return true;
	}

	private static boolean allZero(byte[] value, int from, int to) {
		for (int i = from; i <= to; i++) {
			if (value[i] != 0) {
				return false;
			}
		}
		return true;
	}

	private static boolean isRoutableIpv4(byte[] a) {
		int o1 = a[0] & 0xFF;
		int o2 = a[1] & 0xFF;
		if (o1 == 0 || o1 == 127) {
			return false; // 0.0.0.0/8 unspecified and 127.0.0.0/8 loopback
		}
		if (o1 == 169 && o2 == 254) {
			return false; // 169.254.0.0/16 link-local
		}
		if (o1 >= 224 && o1 < 240) {
			return false; // 224.0.0.0/4 multicast
		}
		if (o1 == 192 && o2 == 88 && (a[2] & 0xFF) == 99) {
			return false; // 192.88.99.0/24 6to4 relay anycast
		}
		if (o1 == 10 || (o1 == 172 && o2 >= 16 && o2 <= 31) || (o1 == 192 && o2 == 168)) {
			return false; // RFC1918 private
		}
		if (o1 == 100 && o2 >= 64 && o2 <= 127) {
			return false; // 100.64.0.0/10 carrier-grade NAT
		}
		if (o1 == 198 && o2 == 18 || o1 == 198 && o2 == 19) {
			return false; // 198.18.0.0/15 benchmark
		}
		if (o1 == 192 && o2 == 0) {
			return false; // 192.0.0.0/24 protocol assignments and 192.0.2.0/24 documentation
		}
		if (o1 == 198 && o2 == 51 && (a[2] & 0xFF) == 100) {
			return false; // 198.51.100.0/24 documentation
		}
		if (o1 == 203 && o2 == 0 && (a[2] & 0xFF) == 113) {
			return false; // 203.0.113.0/24 documentation (TEST-NET-3)
		}
		return o1 < 240; // 240.0.0.0/4 reserved incl. broadcast
	}

	/** Resolved host with every candidate address validated. */
	public record ResolvedTarget(String host, InetAddress[] addresses) {

		public ResolvedTarget {
			if (host == null || host.isBlank()) {
				throw new IllegalArgumentException("host is required");
			}
			if (addresses == null || addresses.length == 0) {
				throw new IllegalArgumentException("addresses are required");
			}
		}
	}

	interface Resolver {

		InetAddress[] lookup(String hostname) throws UnknownHostException;
	}
}
