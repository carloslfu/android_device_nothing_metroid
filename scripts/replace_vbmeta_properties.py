#!/usr/bin/env python3
"""Sign one vbmeta descriptor set with properties copied from another."""

import argparse
import importlib.util
from pathlib import Path


def load_avbtool(path: Path):
    spec = importlib.util.spec_from_file_location("phone_md_avbtool", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load avbtool from {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def parse_image(avbtool, tool, path: Path):
    image = avbtool.ImageHandler(str(path), read_only=True)
    _, header, descriptors, _ = tool._parse_image(image)
    return header, descriptors


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--avbtool", required=True, type=Path)
    parser.add_argument("--descriptors-from", required=True, type=Path)
    parser.add_argument("--properties-from", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--key", required=True, type=Path)
    parser.add_argument("--algorithm", default="SHA256_RSA2048")
    parser.add_argument("--rollback-index", required=True, type=int)
    parser.add_argument("--partition-size", default=65536, type=int)
    args = parser.parse_args()

    avbtool = load_avbtool(args.avbtool)
    tool = avbtool.Avb()
    descriptor_header, source_descriptors = parse_image(
        avbtool, tool, args.descriptors_from
    )
    property_header, property_descriptors = parse_image(
        avbtool, tool, args.properties_from
    )

    properties = [
        descriptor
        for descriptor in property_descriptors
        if isinstance(descriptor, avbtool.AvbPropertyDescriptor)
    ]
    integrity_descriptors = [
        descriptor
        for descriptor in source_descriptors
        if not isinstance(descriptor, avbtool.AvbPropertyDescriptor)
    ]
    if not properties:
        raise RuntimeError(f"No AVB properties found in {args.properties_from}")
    if not integrity_descriptors:
        raise RuntimeError(
            f"No non-property descriptors found in {args.descriptors_from}"
        )

    required_minor = max(
        descriptor_header.required_libavb_version_minor,
        property_header.required_libavb_version_minor,
    )
    blob = tool._generate_vbmeta_blob(
        args.algorithm,
        str(args.key),
        None,
        properties + integrity_descriptors,
        [],
        [],
        args.rollback_index,
        0,
        0,
        [],
        [],
        [],
        None,
        None,
        [],
        None,
        None,
        descriptor_header.release_string,
        None,
        required_minor,
    )
    if len(blob) > args.partition_size:
        raise RuntimeError(
            f"Signed vbmeta is {len(blob)} bytes; partition is {args.partition_size}"
        )

    args.output.write_bytes(blob + b"\0" * (args.partition_size - len(blob)))
    partitions = sorted(
        descriptor.partition_name
        for descriptor in integrity_descriptors
        if hasattr(descriptor, "partition_name")
    )
    print(
        f"Wrote {args.output}: {len(properties)} properties; "
        f"partitions={','.join(partitions)}"
    )


if __name__ == "__main__":
    main()
