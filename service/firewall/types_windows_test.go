//go:build windows

/* SPDX-License-Identifier: MIT
 *
 * Copyright (C) 2019-2026 WireGuard LLC. All Rights Reserved.
 */

package firewall

import (
	"testing"
	"unsafe"
)

func TestWfpAbiPrimitiveWidths(t *testing.T) {
	if got := unsafe.Sizeof(wtFwpDataType(0)); got != 4 {
		t.Errorf("Size of wtFwpDataType is %d, want 4 (C enum FWP_DATA_TYPE)", got)
	}
	if got := unsafe.Sizeof(wtFwpmSession0{}.kernelMode); got != 4 {
		t.Errorf("Size of wtFwpmSession0.kernelMode is %d, want 4 (Win32 BOOL)", got)
	}
}

func TestWtFwpByteBlobLayout(t *testing.T) {
	if got := unsafe.Sizeof(wtFwpByteBlob{}); got != wtFwpByteBlob_Size {
		t.Errorf("Size of wtFwpByteBlob is %d, want %d", got, wtFwpByteBlob_Size)
	}
	if got := unsafe.Offsetof(wtFwpByteBlob{}.data); got != wtFwpByteBlob_data_Offset {
		t.Errorf("wtFwpByteBlob.data offset is %d, want %d", got, wtFwpByteBlob_data_Offset)
	}
}

func TestWtFwpmAction0Layout(t *testing.T) {
	if got := unsafe.Sizeof(wtFwpmAction0{}); got != wtFwpmAction0_Size {
		t.Errorf("Size of wtFwpmAction0 is %d, want %d", got, wtFwpmAction0_Size)
	}
	if got := unsafe.Offsetof(wtFwpmAction0{}.filterType); got != wtFwpmAction0_filterType_Offset {
		t.Errorf("wtFwpmAction0.filterType offset is %d, want %d", got, wtFwpmAction0_filterType_Offset)
	}
}

func TestWtFwpFixedArraySizes(t *testing.T) {
	if got := unsafe.Sizeof(wtFwpBitmapArray64{}); got != wtFwpBitmapArray64_Size {
		t.Errorf("Size of wtFwpBitmapArray64 is %d, want %d", got, wtFwpBitmapArray64_Size)
	}
	if got := unsafe.Sizeof(wtFwpByteArray6{}); got != wtFwpByteArray6_Size {
		t.Errorf("Size of wtFwpByteArray6 is %d, want %d", got, wtFwpByteArray6_Size)
	}
	if got := unsafe.Sizeof(wtFwpByteArray16{}); got != wtFwpByteArray16_Size {
		t.Errorf("Size of wtFwpByteArray16 is %d, want %d", got, wtFwpByteArray16_Size)
	}
}

func TestWtFwpConditionValue0Layout(t *testing.T) {
	if got := unsafe.Sizeof(wtFwpConditionValue0{}); got != wtFwpConditionValue0_Size {
		t.Errorf("Size of wtFwpConditionValue0 is %d, want %d", got, wtFwpConditionValue0_Size)
	}
	if got := unsafe.Offsetof(wtFwpConditionValue0{}.value); got != wtFwpConditionValue0_uint8_Offset {
		t.Errorf("wtFwpConditionValue0.value offset is %d, want %d", got, wtFwpConditionValue0_uint8_Offset)
	}
}

func TestWtFwpAddrAndMaskLayout(t *testing.T) {
	if got := unsafe.Sizeof(wtFwpV4AddrAndMask{}); got != wtFwpV4AddrAndMask_Size {
		t.Errorf("Size of wtFwpV4AddrAndMask is %d, want %d", got, wtFwpV4AddrAndMask_Size)
	}
	if got := unsafe.Offsetof(wtFwpV4AddrAndMask{}.mask); got != wtFwpV4AddrAndMask_mask_Offset {
		t.Errorf("wtFwpV4AddrAndMask.mask offset is %d, want %d", got, wtFwpV4AddrAndMask_mask_Offset)
	}
	if got := unsafe.Sizeof(wtFwpV6AddrAndMask{}); got != wtFwpV6AddrAndMask_Size {
		t.Errorf("Size of wtFwpV6AddrAndMask is %d, want %d", got, wtFwpV6AddrAndMask_Size)
	}
	if got := unsafe.Offsetof(wtFwpV6AddrAndMask{}.prefixLength); got != wtFwpV6AddrAndMask_prefixLength_Offset {
		t.Errorf("wtFwpV6AddrAndMask.prefixLength offset is %d, want %d", got, wtFwpV6AddrAndMask_prefixLength_Offset)
	}
}

func TestWtFwpValue0Layout(t *testing.T) {
	if got := unsafe.Sizeof(wtFwpValue0{}); got != wtFwpValue0_Size {
		t.Errorf("Size of wtFwpValue0 is %d, want %d", got, wtFwpValue0_Size)
	}
	if got := unsafe.Offsetof(wtFwpValue0{}.value); got != wtFwpValue0_value_Offset {
		t.Errorf("wtFwpValue0.value offset is %d, want %d", got, wtFwpValue0_value_Offset)
	}
}

func TestWtFwpmDisplayData0Layout(t *testing.T) {
	if got := unsafe.Sizeof(wtFwpmDisplayData0{}); got != wtFwpmDisplayData0_Size {
		t.Errorf("Size of wtFwpmDisplayData0 is %d, want %d", got, wtFwpmDisplayData0_Size)
	}
	if got := unsafe.Offsetof(wtFwpmDisplayData0{}.description); got != wtFwpmDisplayData0_description_Offset {
		t.Errorf("wtFwpmDisplayData0.description offset is %d, want %d", got, wtFwpmDisplayData0_description_Offset)
	}
}

func TestWtFwpmFilterCondition0Layout(t *testing.T) {
	if got := unsafe.Sizeof(wtFwpmFilterCondition0{}); got != wtFwpmFilterCondition0_Size {
		t.Errorf("Size of wtFwpmFilterCondition0 is %d, want %d", got, wtFwpmFilterCondition0_Size)
	}
	if got := unsafe.Offsetof(wtFwpmFilterCondition0{}.matchType); got != wtFwpmFilterCondition0_matchType_Offset {
		t.Errorf("wtFwpmFilterCondition0.matchType offset is %d, want %d", got, wtFwpmFilterCondition0_matchType_Offset)
	}
	if got := unsafe.Offsetof(wtFwpmFilterCondition0{}.conditionValue); got != wtFwpmFilterCondition0_conditionValue_Offset {
		t.Errorf("wtFwpmFilterCondition0.conditionValue offset is %d, want %d", got, wtFwpmFilterCondition0_conditionValue_Offset)
	}
}

func TestWtFwpmFilter0Layout(t *testing.T) {
	if got := unsafe.Sizeof(wtFwpmFilter0{}); got != wtFwpmFilter0_Size {
		t.Errorf("Size of wtFwpmFilter0 is %d, want %d", got, wtFwpmFilter0_Size)
	}
	var s wtFwpmFilter0
	checks := []struct {
		name string
		got  uintptr
		want uintptr
	}{
		{"displayData", unsafe.Offsetof(s.displayData), wtFwpmFilter0_displayData_Offset},
		{"flags", unsafe.Offsetof(s.flags), wtFwpmFilter0_flags_Offset},
		{"providerKey", unsafe.Offsetof(s.providerKey), wtFwpmFilter0_providerKey_Offset},
		{"providerData", unsafe.Offsetof(s.providerData), wtFwpmFilter0_providerData_Offset},
		{"layerKey", unsafe.Offsetof(s.layerKey), wtFwpmFilter0_layerKey_Offset},
		{"subLayerKey", unsafe.Offsetof(s.subLayerKey), wtFwpmFilter0_subLayerKey_Offset},
		{"weight", unsafe.Offsetof(s.weight), wtFwpmFilter0_weight_Offset},
		{"numFilterConditions", unsafe.Offsetof(s.numFilterConditions), wtFwpmFilter0_numFilterConditions_Offset},
		{"filterCondition", unsafe.Offsetof(s.filterCondition), wtFwpmFilter0_filterCondition_Offset},
		{"action", unsafe.Offsetof(s.action), wtFwpmFilter0_action_Offset},
		{"providerContextKey", unsafe.Offsetof(s.providerContextKey), wtFwpmFilter0_providerContextKey_Offset},
		{"reserved", unsafe.Offsetof(s.reserved), wtFwpmFilter0_reserved_Offset},
		{"filterID", unsafe.Offsetof(s.filterID), wtFwpmFilter0_filterID_Offset},
		{"effectiveWeight", unsafe.Offsetof(s.effectiveWeight), wtFwpmFilter0_effectiveWeight_Offset},
	}
	for _, c := range checks {
		if c.got != c.want {
			t.Errorf("wtFwpmFilter0.%s offset is %d, want %d", c.name, c.got, c.want)
		}
	}
}

func TestWtFwpmProvider0Layout(t *testing.T) {
	if got := unsafe.Sizeof(wtFwpmProvider0{}); got != wtFwpProvider0_Size {
		t.Errorf("Size of wtFwpmProvider0 is %d, want %d", got, wtFwpProvider0_Size)
	}
	if got := unsafe.Sizeof(wtFwpProvider0{}); got != wtFwpProvider0_Size {
		t.Errorf("Size of wtFwpProvider0 is %d, want %d", got, wtFwpProvider0_Size)
	}
	var s wtFwpmProvider0
	checks := []struct {
		name string
		got  uintptr
		want uintptr
	}{
		{"displayData", unsafe.Offsetof(s.displayData), wtFwpProvider0_displayData_Offset},
		{"flags", unsafe.Offsetof(s.flags), wtFwpProvider0_flags_Offset},
		{"providerData", unsafe.Offsetof(s.providerData), wtFwpProvider0_providerData_Offset},
		{"serviceName", unsafe.Offsetof(s.serviceName), wtFwpProvider0_serviceName_Offset},
	}
	for _, c := range checks {
		if c.got != c.want {
			t.Errorf("wtFwpmProvider0.%s offset is %d, want %d", c.name, c.got, c.want)
		}
	}
}

func TestWtFwpmSession0Layout(t *testing.T) {
	if got := unsafe.Sizeof(wtFwpmSession0{}); got != wtFwpmSession0_Size {
		t.Errorf("Size of wtFwpmSession0 is %d, want %d", got, wtFwpmSession0_Size)
	}
	var s wtFwpmSession0
	checks := []struct {
		name string
		got  uintptr
		want uintptr
	}{
		{"displayData", unsafe.Offsetof(s.displayData), wtFwpmSession0_displayData_Offset},
		{"flags", unsafe.Offsetof(s.flags), wtFwpmSession0_flags_Offset},
		{"txnWaitTimeoutInMSec", unsafe.Offsetof(s.txnWaitTimeoutInMSec), wtFwpmSession0_txnWaitTimeoutInMSec_Offset},
		{"processId", unsafe.Offsetof(s.processId), wtFwpmSession0_processId_Offset},
		{"sid", unsafe.Offsetof(s.sid), wtFwpmSession0_sid_Offset},
		{"username", unsafe.Offsetof(s.username), wtFwpmSession0_username_Offset},
		{"kernelMode", unsafe.Offsetof(s.kernelMode), wtFwpmSession0_kernelMode_Offset},
	}
	for _, c := range checks {
		if c.got != c.want {
			t.Errorf("wtFwpmSession0.%s offset is %d, want %d", c.name, c.got, c.want)
		}
	}
}

func TestWtFwpmSublayer0Layout(t *testing.T) {
	if got := unsafe.Sizeof(wtFwpmSublayer0{}); got != wtFwpmSublayer0_Size {
		t.Errorf("Size of wtFwpmSublayer0 is %d, want %d", got, wtFwpmSublayer0_Size)
	}
	var s wtFwpmSublayer0
	checks := []struct {
		name string
		got  uintptr
		want uintptr
	}{
		{"displayData", unsafe.Offsetof(s.displayData), wtFwpmSublayer0_displayData_Offset},
		{"flags", unsafe.Offsetof(s.flags), wtFwpmSublayer0_flags_Offset},
		{"providerKey", unsafe.Offsetof(s.providerKey), wtFwpmSublayer0_providerKey_Offset},
		{"providerData", unsafe.Offsetof(s.providerData), wtFwpmSublayer0_providerData_Offset},
		{"weight", unsafe.Offsetof(s.weight), wtFwpmSublayer0_weight_Offset},
	}
	for _, c := range checks {
		if c.got != c.want {
			t.Errorf("wtFwpmSublayer0.%s offset is %d, want %d", c.name, c.got, c.want)
		}
	}
}
