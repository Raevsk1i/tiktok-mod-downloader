"""Tests for input validation."""

import pytest

from tiktok_region_patcher.config import RegionValues
from tiktok_region_patcher.validation import sanitize_apk_filename, validate_region_values


def test_valid_region_defaults():
    validate_region_values(RegionValues())


def test_invalid_country_rejected():
    with pytest.raises(ValueError, match="country"):
        validate_region_values(RegionValues(country_iso="DEU"))


def test_invalid_operator_rejected():
    with pytest.raises(ValueError, match="operator"):
        validate_region_values(RegionValues(operator="abc"))


def test_smali_injection_in_operator_name_rejected():
    with pytest.raises(ValueError, match="operator-name"):
        validate_region_values(RegionValues(operator_name='evil"\nconst-string'))


def test_sanitize_apk_filename_strips_path():
    assert sanitize_apk_filename("../../etc/passwd.apk") == "passwd.apk"
