/* SPDX-License-Identifier: Apache-2.0 */
package main

/*
#include <stdlib.h>
extern char* readCredentialCache(const char* key);
extern int writeCredentialCache(const char* key, const char* value);
*/
import "C"

import (
	"errors"
	"unsafe"
)

type androidCredentialCache struct{}

func init() { defaultCredentialCache = androidCredentialCache{} }

func (androidCredentialCache) Load(key string) ([]byte, error) {
	k := C.CString(key)
	defer C.free(unsafe.Pointer(k))
	raw := C.readCredentialCache(k)
	if raw == nil {
		return nil, errors.New("credential cache unavailable")
	}
	defer C.free(unsafe.Pointer(raw))
	return []byte(C.GoString(raw)), nil
}

func (androidCredentialCache) Save(key string, value []byte) error {
	k, v := C.CString(key), C.CString(string(value))
	defer C.free(unsafe.Pointer(k))
	defer C.free(unsafe.Pointer(v))
	if C.writeCredentialCache(k, v) == 0 {
		return errors.New("credential cache write failed")
	}
	return nil
}
