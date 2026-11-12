//! Request signing: AWS Signature Version 4 (S3 and the S3-compatible stores, Google Cloud
//! HMAC keys) and Azure Shared Key. Plus the UTC dates both need.

use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};

type HmacSha256 = Hmac<Sha256>;

pub fn sha256_hex(data: &[u8]) -> String {
    hex(&Sha256::digest(data))
}

pub fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

pub fn hmac(key: &[u8], data: &[u8]) -> Vec<u8> {
    let mut m = HmacSha256::new_from_slice(key).expect("HMAC takes any key length");
    m.update(data);
    m.finalize().into_bytes().to_vec()
}

/// Percent-encodes as AWS and Azure expect: unreserved characters stay, `/` stays when
/// `keep_slash` is set, everything else becomes `%XX`.
pub fn uri_encode(s: &str, keep_slash: bool) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        let c = b as char;
        if c.is_ascii_alphanumeric() || matches!(c, '-' | '.' | '_' | '~') || (keep_slash && c == '/') {
            out.push(c);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// Civil date and time in UTC from seconds since 1970.
pub fn utc(secs: i64) -> (i64, u32, u32, u32, u32, u32, u32) {
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    // Howard Hinnant's days-to-civil.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let y = if m <= 2 { y + 1 } else { y };
    // 1970-01-01 was a Thursday.
    let weekday = ((days + 4).rem_euclid(7)) as u32;
    (y, m, d, (rem / 3600) as u32, ((rem % 3600) / 60) as u32, (rem % 60) as u32, weekday)
}

pub fn now_secs() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs() as i64)
}

/// `20130524T000000Z` and `20130524`.
pub fn amz_dates(secs: i64) -> (String, String) {
    let (y, m, d, hh, mm, ss, _) = utc(secs);
    (format!("{y:04}{m:02}{d:02}T{hh:02}{mm:02}{ss:02}Z"), format!("{y:04}{m:02}{d:02}"))
}

/// `Fri, 24 May 2013 00:00:00 GMT`, as Azure's `x-ms-date` wants it.
pub fn rfc1123(secs: i64) -> String {
    const DAYS: [&str; 7] = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
    const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
    let (y, m, d, hh, mm, ss, wd) = utc(secs);
    format!("{}, {d:02} {} {y:04} {hh:02}:{mm:02}:{ss:02} GMT", DAYS[wd as usize], MONTHS[m as usize - 1])
}

pub struct SigV4<'a> {
    pub access: &'a str,
    pub secret: &'a str,
    pub region: &'a str,
    pub service: &'a str,
}

impl SigV4<'_> {
    /// The `Authorization` header for one request. `headers` are lowercase names with trimmed
    /// values and must include `host`; `query` is the canonical query string, already encoded
    /// and sorted.
    #[allow(clippy::too_many_arguments)]
    pub fn authorization(&self, method: &str, uri: &str, query: &str, headers: &[(&str, String)], payload_hash: &str, amz_date: &str, date: &str) -> String {
        let mut hs: Vec<(&str, &str)> = headers.iter().map(|(k, v)| (*k, v.as_str())).collect();
        hs.sort_by(|a, b| a.0.cmp(b.0));
        let canonical_headers: String = hs.iter().map(|(k, v)| format!("{k}:{}\n", v.trim())).collect();
        let signed: Vec<&str> = hs.iter().map(|(k, _)| *k).collect();
        let signed = signed.join(";");
        let canonical = format!("{method}\n{uri}\n{query}\n{canonical_headers}\n{signed}\n{payload_hash}");
        let scope = format!("{date}/{}/{}/aws4_request", self.region, self.service);
        let to_sign = format!("AWS4-HMAC-SHA256\n{amz_date}\n{scope}\n{}", sha256_hex(canonical.as_bytes()));
        let k_date = hmac(format!("AWS4{}", self.secret).as_bytes(), date.as_bytes());
        let k_region = hmac(&k_date, self.region.as_bytes());
        let k_service = hmac(&k_region, self.service.as_bytes());
        let k_signing = hmac(&k_service, b"aws4_request");
        let signature = hex(&hmac(&k_signing, to_sign.as_bytes()));
        format!("AWS4-HMAC-SHA256 Credential={}/{scope}, SignedHeaders={signed}, Signature={signature}", self.access)
    }
}

/// Azure Shared Key: the signature over the request's fixed fields, its `x-ms-*` headers and
/// its resource. `content_length` is empty for a request with no body.
pub fn azure_shared_key(key: &[u8], account: &str, method: &str, content_length: &str, x_ms: &[(&str, String)], resource: &str) -> String {
    let mut hs: Vec<(String, &str)> = x_ms.iter().map(|(k, v)| (k.to_lowercase(), v.as_str())).collect();
    hs.sort_by(|a, b| a.0.cmp(&b.0));
    let canonical_headers: String = hs.iter().map(|(k, v)| format!("{k}:{}\n", v.trim())).collect();
    // VERB, Content-Encoding, Content-Language, Content-Length, Content-MD5, Content-Type, Date,
    // If-Modified-Since, If-Match, If-None-Match, If-Unmodified-Since, Range.
    let to_sign = format!("{method}\n\n\n{content_length}\n\n\n\n\n\n\n\n\n{canonical_headers}{resource}");
    use base64::Engine;
    let sig = base64::engine::general_purpose::STANDARD.encode(hmac(key, to_sign.as_bytes()));
    format!("SharedKey {account}:{sig}")
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The "GET Object" example in AWS's Signature Version 4 documentation.
    #[test]
    fn sigv4_matches_the_aws_example() {
        let s = SigV4 { access: "AKIAIOSFODNN7EXAMPLE", secret: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", region: "us-east-1", service: "s3" };
        let empty = sha256_hex(b"");
        let headers = [
            ("host", "examplebucket.s3.amazonaws.com".to_string()),
            ("range", "bytes=0-9".to_string()),
            ("x-amz-content-sha256", empty.clone()),
            ("x-amz-date", "20130524T000000Z".to_string()),
        ];
        let auth = s.authorization("GET", "/test.txt", "", &headers, &empty, "20130524T000000Z", "20130524");
        assert!(auth.ends_with("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"), "{auth}");
    }

    #[test]
    fn dates_and_encoding() {
        assert_eq!(amz_dates(1_369_353_600), ("20130524T000000Z".to_string(), "20130524".to_string()));
        assert_eq!(rfc1123(1_369_353_600), "Fri, 24 May 2013 00:00:00 GMT");
        assert_eq!(uri_encode("Drafts/On slow software.md", true), "Drafts/On%20slow%20software.md");
        assert_eq!(uri_encode("a/b", false), "a%2Fb");
    }
}
