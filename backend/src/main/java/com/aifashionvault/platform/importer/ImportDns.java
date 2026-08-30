package com.aifashionvault.platform.importer;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

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
		// 2001:db8::/32 documentation
		return !((a[0] & 0xFF) == 0x20 && (a[1] & 0xFF) == 0x01 && (a[2] & 0xFF) == 0x0D && (a[3] & 0xFF) == 0xB8);
	}

	private static boolean isRoutableIpv4(byte[] a) {
		int o1 = a[0] & 0xFF;
		int o2 = a[1] & 0xFF;
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
