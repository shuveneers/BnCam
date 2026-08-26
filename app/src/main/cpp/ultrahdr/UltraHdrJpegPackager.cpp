#include "UltraHdrJpegPackager.h"

#include <opencv2/imgcodecs.hpp>
#include <opencv2/core.hpp>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <iomanip>
#include <limits>
#include <sstream>
#include <utility>

namespace bncam::ultrahdr {
namespace {

constexpr std::uint8_t kJpegMarkerPrefix = 0xFF;
constexpr std::uint8_t kSoi = 0xD8;
constexpr std::uint8_t kApp1 = 0xE1;
constexpr std::uint8_t kApp2 = 0xE2;
constexpr char kXmpNamespace[] = "http://ns.adobe.com/xap/1.0/";
constexpr char kIccIdentifier[] = "ICC_PROFILE";
constexpr char kHdrgmNamespace[] = "http://ns.adobe.com/hdr-gain-map/1.0/";
constexpr char kContainerNamespace[] = "http://ns.google.com/photos/1.0/container/";
constexpr char kContainerItemNamespace[] = "http://ns.google.com/photos/1.0/container/item/";
constexpr std::size_t kMpfPayloadSize = 82u;

void appendU16Be(std::vector<std::uint8_t>& out, std::uint16_t v) {
    out.push_back(static_cast<std::uint8_t>((v >> 8u) & 0xFFu));
    out.push_back(static_cast<std::uint8_t>(v & 0xFFu));
}

void appendU32Be(std::vector<std::uint8_t>& out, std::uint32_t v) {
    out.push_back(static_cast<std::uint8_t>((v >> 24u) & 0xFFu));
    out.push_back(static_cast<std::uint8_t>((v >> 16u) & 0xFFu));
    out.push_back(static_cast<std::uint8_t>((v >> 8u) & 0xFFu));
    out.push_back(static_cast<std::uint8_t>(v & 0xFFu));
}

void patchU32Be(std::vector<std::uint8_t>& out, std::size_t offset, std::uint32_t v) {
    out[offset + 0] = static_cast<std::uint8_t>((v >> 24u) & 0xFFu);
    out[offset + 1] = static_cast<std::uint8_t>((v >> 16u) & 0xFFu);
    out[offset + 2] = static_cast<std::uint8_t>((v >> 8u) & 0xFFu);
    out[offset + 3] = static_cast<std::uint8_t>(v & 0xFFu);
}

std::int32_t toS15Fixed16(float value) {
    const double scaled = std::round(static_cast<double>(value) * 65536.0);
    const double bounded = std::clamp(
            scaled,
            static_cast<double>(std::numeric_limits<std::int32_t>::min()),
            static_cast<double>(std::numeric_limits<std::int32_t>::max()));
    return static_cast<std::int32_t>(bounded);
}

void appendFixed(std::vector<std::uint8_t>& out, float value) {
    appendU32Be(out, static_cast<std::uint32_t>(toS15Fixed16(value)));
}

std::vector<std::uint8_t> makeMlucTag(const std::string& text) {
    const std::size_t rawSize = 28u + text.size() * 2u;
    const std::size_t aligned = (rawSize + 3u) & ~std::size_t(3u);
    std::vector<std::uint8_t> out;
    out.reserve(aligned);
    appendU32Be(out, 0x6D6C7563u); // mluc
    appendU32Be(out, 0u);
    appendU32Be(out, 1u);
    appendU32Be(out, 12u);
    appendU32Be(out, 0x656E5553u); // enUS
    appendU32Be(out, static_cast<std::uint32_t>(text.size() * 2u));
    appendU32Be(out, 28u);
    for (unsigned char c : text) {
        out.push_back(0u);
        out.push_back(c);
    }
    out.resize(aligned, 0u);
    return out;
}

std::vector<std::uint8_t> makeXyzTag(float x, float y, float z) {
    std::vector<std::uint8_t> out;
    out.reserve(20u);
    appendU32Be(out, 0x58595A20u); // XYZ 
    appendU32Be(out, 0u);
    appendFixed(out, x);
    appendFixed(out, y);
    appendFixed(out, z);
    return out;
}

std::vector<std::uint8_t> makeSrgbTrcTag() {
    std::vector<std::uint8_t> out;
    out.reserve(40u);
    appendU32Be(out, 0x70617261u); // para
    appendU32Be(out, 0u);
    appendU16Be(out, 4u);          // ICC parametric curve type 4
    appendU16Be(out, 0u);
    appendFixed(out, 2.4f);
    appendFixed(out, 1.0f / 1.055f);
    appendFixed(out, 0.055f / 1.055f);
    appendFixed(out, 1.0f / 12.92f);
    appendFixed(out, 0.04045f);
    appendFixed(out, 0.0f);
    appendFixed(out, 0.0f);
    return out;
}

std::vector<std::uint8_t> makeSrgbIccProfilePayload() {
    struct Tag { std::uint32_t signature; std::vector<std::uint8_t> data; };
    constexpr float kD50X = 0.9642f;
    constexpr float kD50Y = 1.0f;
    constexpr float kD50Z = 0.8249f;
    // D50-adapted sRGB matrix, matching the AOSP/libultrahdr profile generator.
    const std::array<std::array<float, 3>, 3> srgb = {{
            {{0.436065674f, 0.385147095f, 0.143066406f}},
            {{0.222488403f, 0.716873169f, 0.060607910f}},
            {{0.013916016f, 0.097076416f, 0.714096069f}},
    }};

    const auto trc = makeSrgbTrcTag();
    std::vector<Tag> tags;
    tags.push_back({0x64657363u, makeMlucTag("BnCam sRGB")}); // desc
    tags.push_back({0x7258595Au, makeXyzTag(srgb[0][0], srgb[1][0], srgb[2][0])});
    tags.push_back({0x6758595Au, makeXyzTag(srgb[0][1], srgb[1][1], srgb[2][1])});
    tags.push_back({0x6258595Au, makeXyzTag(srgb[0][2], srgb[1][2], srgb[2][2])});
    tags.push_back({0x77747074u, makeXyzTag(kD50X, kD50Y, kD50Z)});
    tags.push_back({0x72545243u, trc});
    tags.push_back({0x67545243u, trc});
    tags.push_back({0x62545243u, trc});
    tags.push_back({0x63707274u, makeMlucTag("BnCam")});

    constexpr std::size_t kHeaderSize = 128u;
    const std::size_t tableSize = 4u + tags.size() * 12u;
    std::size_t dataOffset = kHeaderSize + tableSize;
    std::size_t profileSize = dataOffset;
    for (const auto& tag : tags) profileSize += tag.data.size();

    std::vector<std::uint8_t> profile(profileSize, 0u);
    auto write32At = [&](std::size_t o, std::uint32_t v) {
        profile[o + 0] = static_cast<std::uint8_t>((v >> 24u) & 0xFFu);
        profile[o + 1] = static_cast<std::uint8_t>((v >> 16u) & 0xFFu);
        profile[o + 2] = static_cast<std::uint8_t>((v >> 8u) & 0xFFu);
        profile[o + 3] = static_cast<std::uint8_t>(v & 0xFFu);
    };
    write32At(0u, static_cast<std::uint32_t>(profileSize));
    write32At(8u, 0x04300000u);     // ICC v4.3
    write32At(12u, 0x6D6E7472u);    // mntr
    write32At(16u, 0x52474220u);    // RGB 
    write32At(20u, 0x58595A20u);    // XYZ 
    write32At(36u, 0x61637370u);    // acsp
    write32At(64u, 1u);             // relative colorimetric
    write32At(68u, static_cast<std::uint32_t>(toS15Fixed16(kD50X)));
    write32At(72u, static_cast<std::uint32_t>(toS15Fixed16(kD50Y)));
    write32At(76u, static_cast<std::uint32_t>(toS15Fixed16(kD50Z)));
    write32At(128u, static_cast<std::uint32_t>(tags.size()));

    std::size_t tablePos = 132u;
    std::size_t payloadPos = dataOffset;
    for (const auto& tag : tags) {
        write32At(tablePos + 0u, tag.signature);
        write32At(tablePos + 4u, static_cast<std::uint32_t>(payloadPos));
        write32At(tablePos + 8u, static_cast<std::uint32_t>(tag.data.size()));
        std::copy(tag.data.begin(), tag.data.end(), profile.begin() + static_cast<std::ptrdiff_t>(payloadPos));
        tablePos += 12u;
        payloadPos += tag.data.size();
    }
    return profile;
}

bool startsWithJpegSoi(const std::vector<std::uint8_t>& jpeg) {
    return jpeg.size() >= 4u && jpeg[0] == kJpegMarkerPrefix && jpeg[1] == kSoi;
}

bool containsAscii(const std::vector<std::uint8_t>& data, const char* text) {
    const auto n = std::strlen(text);
    if (n == 0u || data.size() < n) return false;
    return std::search(data.begin(), data.end(), text, text + n) != data.end();
}

bool hasIccProfile(const std::vector<std::uint8_t>& jpeg) {
    return containsAscii(jpeg, kIccIdentifier);
}

bool appendJpegSegment(std::vector<std::uint8_t>& out, std::uint8_t marker,
                       const std::vector<std::uint8_t>& payload) {
    if (payload.size() + 2u > 65535u) return false;
    out.push_back(kJpegMarkerPrefix);
    out.push_back(marker);
    appendU16Be(out, static_cast<std::uint16_t>(payload.size() + 2u));
    out.insert(out.end(), payload.begin(), payload.end());
    return true;
}

bool appendXmpSegment(std::vector<std::uint8_t>& out, const std::string& xmp) {
    std::vector<std::uint8_t> payload;
    payload.reserve(sizeof(kXmpNamespace) + xmp.size());
    payload.insert(payload.end(), kXmpNamespace, kXmpNamespace + sizeof(kXmpNamespace));
    payload.insert(payload.end(), xmp.begin(), xmp.end());
    return appendJpegSegment(out, kApp1, payload);
}

bool appendIccSegment(std::vector<std::uint8_t>& out) {
    const auto profile = makeSrgbIccProfilePayload();
    std::vector<std::uint8_t> payload;
    payload.reserve(14u + profile.size());
    payload.insert(payload.end(), kIccIdentifier, kIccIdentifier + sizeof(kIccIdentifier));
    payload.push_back(1u); // chunk index
    payload.push_back(1u); // chunk count
    payload.insert(payload.end(), profile.begin(), profile.end());
    return appendJpegSegment(out, kApp2, payload);
}

std::string fmtFloat(float value) {
    std::ostringstream out;
    out << std::fixed << std::setprecision(6) << value;
    return out.str();
}

std::string makeGainmapXmp(const GainmapPayload& gainmap) {
    const float minLog2 = std::log2(std::max(1.0e-6f, gainmap.minContentBoost));
    const float maxLog2 = std::log2(std::max(gainmap.minContentBoost, gainmap.maxContentBoost));
    std::ostringstream x;
    x << "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
      << "<rdf:Description rdf:about=\"\" xmlns:hdrgm=\"" << kHdrgmNamespace << "\""
      << " hdrgm:Version=\"1.0\" hdrgm:BaseRenditionIsHDR=\"False\""
      << " hdrgm:GainMapMin=\"" << fmtFloat(minLog2) << "\""
      << " hdrgm:GainMapMax=\"" << fmtFloat(maxLog2) << "\""
      << " hdrgm:Gamma=\"" << fmtFloat(gainmap.gamma) << "\""
      << " hdrgm:OffsetSDR=\"" << fmtFloat(gainmap.offsetSdr) << "\""
      << " hdrgm:OffsetHDR=\"" << fmtFloat(gainmap.offsetHdr) << "\""
      << " hdrgm:HDRCapacityMin=\"0.000000\""
      << " hdrgm:HDRCapacityMax=\"" << fmtFloat(maxLog2) << "\"/>"
      << "</rdf:RDF></x:xmpmeta>";
    return x.str();
}

std::string makePrimaryXmp(std::size_t secondaryLength) {
    std::ostringstream x;
    x << "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
      << "<rdf:Description rdf:about=\"\" xmlns:hdrgm=\"" << kHdrgmNamespace << "\""
      << " xmlns:Container=\"" << kContainerNamespace << "\""
      << " xmlns:Item=\"" << kContainerItemNamespace << "\" hdrgm:Version=\"1.0\">"
      << "<Container:Directory><rdf:Seq>"
      << "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/></rdf:li>"
      << "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\" Item:Length=\""
      << secondaryLength << "\"/></rdf:li>"
      << "</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>";
    return x.str();
}

std::vector<std::uint8_t> makeMpfPayload(std::size_t primaryImageSize,
                                         std::size_t secondaryImageSize,
                                         std::size_t secondaryOffset) {
    if (primaryImageSize > std::numeric_limits<std::uint32_t>::max() ||
        secondaryImageSize > std::numeric_limits<std::uint32_t>::max() ||
        secondaryOffset > std::numeric_limits<std::uint32_t>::max()) return {};
    std::vector<std::uint8_t> out;
    out.reserve(kMpfPayloadSize);
    out.insert(out.end(), {'M', 'P', 'F', 0});
    out.insert(out.end(), {'M', 'M', 0, 0x2A});
    appendU32Be(out, 8u);          // index IFD offset from TIFF header
    appendU16Be(out, 3u);          // tag count
    appendU16Be(out, 0xB000u); appendU16Be(out, 7u); appendU32Be(out, 4u); out.insert(out.end(), {'0','1','0','0'});
    appendU16Be(out, 0xB001u); appendU16Be(out, 4u); appendU32Be(out, 1u); appendU32Be(out, 2u);
    appendU16Be(out, 0xB002u); appendU16Be(out, 7u); appendU32Be(out, 32u); appendU32Be(out, 50u);
    appendU32Be(out, 0u);          // attribute IFD offset
    appendU32Be(out, 0x00030000u); // JPEG + representative primary
    appendU32Be(out, static_cast<std::uint32_t>(primaryImageSize));
    appendU32Be(out, 0u);
    appendU16Be(out, 0u); appendU16Be(out, 0u);
    appendU32Be(out, 0u);
    appendU32Be(out, static_cast<std::uint32_t>(secondaryImageSize));
    appendU32Be(out, static_cast<std::uint32_t>(secondaryOffset));
    appendU16Be(out, 0u); appendU16Be(out, 0u);
    if (out.size() != kMpfPayloadSize) return {};
    return out;
}

std::size_t xmpSegmentPayloadSize(const std::string& xmp) {
    return sizeof(kXmpNamespace) + xmp.size();
}

}  // namespace

PackageResult packageUltraHdrJpeg(const std::vector<std::uint8_t>& baseJpeg,
                                  const GainmapPayload& gainmap,
                                  int gainmapJpegQuality) {
    PackageResult result;
    if (!startsWithJpegSoi(baseJpeg)) {
        result.failureReason = "BASE_JPEG_INVALID";
        return result;
    }
    if (gainmap.width == 0u || gainmap.height == 0u || gainmap.rowStrideBytes < gainmap.width ||
        gainmap.pixels.size() < static_cast<std::size_t>(gainmap.rowStrideBytes) * gainmap.height) {
        result.failureReason = "GAINMAP_PAYLOAD_INVALID";
        return result;
    }
    if (!(gainmap.maxContentBoost > 1.0f) || !std::isfinite(gainmap.maxContentBoost)) {
        result.failureReason = "GAINMAP_HEADROOM_INVALID";
        return result;
    }

    // Pixels are already final 8-bit gainmap values produced, downsampled and rotated by Vulkan.
    // CPU only performs JPEG entropy/container encoding here; no gainmap pixel math is performed.
    const cv::Mat map(static_cast<int>(gainmap.height), static_cast<int>(gainmap.width), CV_8UC1,
                      const_cast<std::uint8_t*>(gainmap.pixels.data()), gainmap.rowStrideBytes);
    std::vector<std::uint8_t> gainmapJpeg;
    const std::vector<int> params = {cv::IMWRITE_JPEG_QUALITY, std::clamp(gainmapJpegQuality, 70, 100)};
    if (!cv::imencode(".jpg", map, gainmapJpeg, params) || !startsWithJpegSoi(gainmapJpeg)) {
        result.failureReason = "GAINMAP_JPEG_ENCODE_FAILED";
        return result;
    }

    const std::string secondaryXmp = makeGainmapXmp(gainmap);
    // Secondary image: SOI + APP1 marker/length/payload + original gainmap JPEG without its SOI.
    const std::size_t secondaryLength = 2u + 4u + xmpSegmentPayloadSize(secondaryXmp) + gainmapJpeg.size() - 2u;
    const std::string primaryXmp = makePrimaryXmp(secondaryLength);

    std::vector<std::uint8_t> out;
    out.reserve(baseJpeg.size() + gainmapJpeg.size() + primaryXmp.size() + secondaryXmp.size() + 2048u);
    out.push_back(kJpegMarkerPrefix);
    out.push_back(kSoi);
    if (!appendXmpSegment(out, primaryXmp)) {
        result.failureReason = "PRIMARY_XMP_TOO_LARGE";
        return result;
    }
    if (!hasIccProfile(baseJpeg)) {
        if (!appendIccSegment(out)) {
            result.failureReason = "ICC_SEGMENT_FAILED";
            return result;
        }
        result.iccInjected = true;
    }

    // Mirror AOSP/libultrahdr MPF offset accounting: pos is immediately before MPF marker.
    const std::size_t posBeforeMpf = out.size();
    constexpr std::size_t mpfLengthFieldValue = kMpfPayloadSize + 2u;
    const std::size_t primaryImageSize = posBeforeMpf + mpfLengthFieldValue + baseJpeg.size();
    const std::size_t secondaryOffset = primaryImageSize - posBeforeMpf - 8u;
    const auto mpf = makeMpfPayload(primaryImageSize, secondaryLength, secondaryOffset);
    if (mpf.empty() || !appendJpegSegment(out, kApp2, mpf)) {
        result.failureReason = "MPF_BUILD_FAILED";
        return result;
    }

    out.insert(out.end(), baseJpeg.begin() + 2, baseJpeg.end());
    out.push_back(kJpegMarkerPrefix);
    out.push_back(kSoi);
    if (!appendXmpSegment(out, secondaryXmp)) {
        result.failureReason = "GAINMAP_XMP_TOO_LARGE";
        return result;
    }
    out.insert(out.end(), gainmapJpeg.begin() + 2, gainmapJpeg.end());

    if (!validateUltraHdrJpeg(out)) {
        result.failureReason = "PACKAGED_JPEG_VALIDATION_FAILED";
        return result;
    }
    result.success = true;
    result.jpeg = std::move(out);
    return result;
}

bool validateUltraHdrJpeg(const std::vector<std::uint8_t>& jpeg) {
    if (!startsWithJpegSoi(jpeg)) return false;
    if (!containsAscii(jpeg, "hdrgm:Version=\"1.0\"")) return false;
    if (!containsAscii(jpeg, "Item:Semantic=\"GainMap\"")) return false;
    if (!containsAscii(jpeg, "MPF")) return false;
    if (!containsAscii(jpeg, "ICC_PROFILE")) return false;
    std::size_t soiCount = 0u;
    for (std::size_t i = 0; i + 1u < jpeg.size(); ++i) {
        if (jpeg[i] == kJpegMarkerPrefix && jpeg[i + 1u] == kSoi) ++soiCount;
    }
    return soiCount >= 2u;
}

}  // namespace bncam::ultrahdr
