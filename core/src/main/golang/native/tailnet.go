package main

//#include "bridge.h"
import "C"

import "cfa/native/tailnet"

//export tailscaleCommand
func tailscaleCommand(command, content C.c_string) *C.char {
	return marshalJson(tailnet.Command(C.GoString(command), C.GoString(content)))
}
