"""Unit tests for the smali patching logic."""

from pathlib import Path

from tiktok_region_patcher.config import RegionValues
from tiktok_region_patcher.patcher import patch_smali_text, patch_tree


REGION = RegionValues()  # de / 26201 / Telekom.de


def test_patch_network_country_iso():
    smali = (
        "    .line 42\n"
        "    invoke-virtual {v2}, Landroid/telephony/TelephonyManager;"
        "->getNetworkCountryIso()Ljava/lang/String;\n"
        "    move-result-object v2\n"
        "    return-object v2\n"
    )
    new_text, patches = patch_smali_text(smali, REGION)
    assert new_text is not None
    assert len(patches) == 1
    assert patches[0].method == "getNetworkCountryIso"
    assert patches[0].register == "v2"
    assert patches[0].value == "de"
    assert 'const-string v2, "de"' in new_text
    # The original invoke and move-result must be gone.
    assert "getNetworkCountryIso" not in new_text
    assert "move-result-object v2" not in new_text
    # Unrelated line preserved.
    assert "return-object v2" in new_text


def test_patch_sim_operator_returns_mcc():
    smali = (
        "    invoke-virtual {p1}, Landroid/telephony/TelephonyManager;"
        "->getSimOperator()Ljava/lang/String;\n"
        "    move-result-object p1\n"
    )
    new_text, patches = patch_smali_text(smali, REGION)
    assert patches[0].value == "26201"
    assert 'const-string p1, "26201"' in new_text


def test_operator_name_method():
    smali = (
        "    invoke-virtual {v0}, Landroid/telephony/TelephonyManager;"
        "->getNetworkOperatorName()Ljava/lang/String;\n"
        "    move-result-object v0\n"
    )
    new_text, patches = patch_smali_text(smali, REGION)
    assert patches[0].value == "Telekom.de"
    assert 'const-string v0, "Telekom.de"' in new_text


def test_debug_directive_between_invoke_and_move_result_is_kept():
    smali = (
        "    invoke-virtual {v3}, Landroid/telephony/TelephonyManager;"
        "->getSimCountryIso()Ljava/lang/String;\n"
        "    .line 7\n"
        "    move-result-object v3\n"
    )
    new_text, patches = patch_smali_text(smali, REGION)
    assert len(patches) == 1
    assert ".line 7" in new_text
    assert 'const-string v3, "de"' in new_text


def test_result_discarded_is_recorded_in_skipped():
    smali = (
        "    invoke-virtual {v1}, Landroid/telephony/TelephonyManager;"
        "->getSimOperator()Ljava/lang/String;\n"
        "    return-void\n"
    )
    skipped: list = []
    new_text, patches = patch_smali_text(smali, REGION, path=Path("Test.smali"), skipped=skipped)
    assert new_text is None
    assert patches == []
    assert len(skipped) == 1


def test_non_string_method_untouched():
    # getPhoneType returns I, not a String; our regex must ignore it.
    smali = (
        "    invoke-virtual {v0}, Landroid/telephony/TelephonyManager;"
        "->getPhoneType()I\n"
        "    move-result v0\n"
    )
    new_text, patches = patch_smali_text(smali, REGION)
    assert new_text is None
    assert patches == []


def test_file_without_reference_is_skipped():
    smali = "    const/4 v0, 0x0\n    return-void\n"
    new_text, patches = patch_smali_text(smali, REGION)
    assert new_text is None
    assert patches == []


def test_range_invoke_variant():
    smali = (
        "    invoke-virtual/range {v10 .. v10}, Landroid/telephony/TelephonyManager;"
        "->getNetworkOperator()Ljava/lang/String;\n"
        "    move-result-object v10\n"
    )
    new_text, patches = patch_smali_text(smali, REGION)
    assert patches[0].value == "26201"
    assert 'const-string v10, "26201"' in new_text


def test_custom_region_values():
    region = RegionValues(country_iso="fr", operator="20801", operator_name="Orange")
    smali = (
        "    invoke-virtual {v0}, Landroid/telephony/TelephonyManager;"
        "->getNetworkCountryIso()Ljava/lang/String;\n"
        "    move-result-object v0\n"
    )
    new_text, patches = patch_smali_text(smali, region)
    assert 'const-string v0, "fr"' in new_text


def test_patch_tree_end_to_end(tmp_path: Path):
    root = tmp_path / "smali"
    pkg = root / "com" / "example"
    pkg.mkdir(parents=True)
    (pkg / "Region.smali").write_text(
        "    invoke-virtual {v0}, Landroid/telephony/TelephonyManager;"
        "->getNetworkCountryIso()Ljava/lang/String;\n"
        "    move-result-object v0\n",
        encoding="utf-8",
    )
    (pkg / "Other.smali").write_text(
        "    const/4 v0, 0x0\n    return-void\n", encoding="utf-8"
    )

    report = patch_tree(root, REGION)
    assert report.scanned_files == 2
    assert report.referencing_files == 1
    assert report.patched_count == 1
    patched = (pkg / "Region.smali").read_text(encoding="utf-8")
    assert 'const-string v0, "de"' in patched
