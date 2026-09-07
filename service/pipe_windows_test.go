package main

import (
    "encoding/binary"
    "encoding/json"
    "io"
    "testing"
    "time"
    "github.com/Microsoft/go-winio"
    "golang.org/x/sys/windows"
)

func TestAuthenticatedStatusOverNamedPipe(t *testing.T) {
    user,err:=windows.GetCurrentProcessToken().GetTokenUser();if err!=nil{t.Fatal(err)}
    owner:=user.User.Sid.String()
    path:=`\\.\pipe\YumiTest-`+randomSecret()
    listener,err:=winio.ListenPipe(path,&winio.PipeConfig{SecurityDescriptor:"D:P(A;;GA;;;"+owner+")"});if err!=nil{t.Fatal(err)};defer listener.Close()
    s:=&supervisor{status:Status{Version:1,State:"disconnected"}}
    done:=make(chan struct{})
    go func(){defer close(done);c,e:=listener.Accept();if e==nil{serveClient(c,owner,s)}}()
    timeout:=time.Second
    conn,err:=winio.DialPipe(path,&timeout);if err!=nil{t.Fatal(err)};defer conn.Close()
    _=conn.SetDeadline(time.Now().Add(3*time.Second))
    request:=[]byte(`{"version":1,"command":"status"}`)
    if err=binary.Write(conn,binary.BigEndian,uint32(len(request)));err!=nil{t.Fatal(err)}
    if _,err=conn.Write(request);err!=nil{t.Fatal(err)}
    var size uint32;if err=binary.Read(conn,binary.BigEndian,&size);err!=nil{t.Fatal(err)}
    data:=make([]byte,size);if _,err=io.ReadFull(conn,data);err!=nil{t.Fatal(err)}
    var status Status;if json.Unmarshal(data,&status)!=nil||status.State!="disconnected"{t.Fatal("invalid reply")}
    <-done
}

func TestIdleStopDoesNotStartWorker(t *testing.T){
    s:=&supervisor{status:Status{Version:1,State:"disconnected"}}
    s.stop();s.stop()
    if s.child!=nil||s.snapshot().State!="disconnected"{t.Fatal("idle stop changed worker state")}
}
