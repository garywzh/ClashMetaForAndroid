package main

//#include "bridge.h"
import "C"

import (
	"cfa/native/tailnet"
	"strings"
)

//export tailscaleCommand
func tailscaleCommand(command, content C.c_string) *C.char {
	if strings.HasPrefix(C.GoString(command), "taildrop-") {
		return marshalJson(tailnet.TaildropCommand(C.GoString(command), C.GoString(content)))
	}
	return marshalJson(tailnet.Command(C.GoString(command), C.GoString(content)))
}
