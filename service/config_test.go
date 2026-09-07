package main

import (
    "encoding/json"
    "strings"
    "testing"
    "os"
    "path/filepath"

    "github.com/xtls/xray-core/infra/conf/serial"
)

func TestJVMRequestsAgainstPinnedCore(t *testing.T) {
    files,err:=filepath.Glob("../logic/build/service-requests/*.json");if err!=nil{t.Fatal(err)}
    if len(files)<100{t.Fatal("Run :logic:test first to generate the JVM request matrix")}
    for _,file:=range files{t.Run(filepath.Base(file),func(t *testing.T){
        data,err:=os.ReadFile(file);if err!=nil{t.Fatal(err)}
        r,err:=decodeRequest(data);if err!=nil{t.Fatal(err)}
        encoded,_:=json.Marshal(buildConfig(r,19090,"test-user","test-password"))
        if _,err:=serial.LoadJSONConfig(strings.NewReader(string(encoded)));err!=nil{t.Fatal(err)}
    })}
}

func fixture() Request { return Request{Version:1,Command:"connect",NodeID:"fixture",Outbound:obj{"protocol":"socks","settings":obj{"servers":[]any{obj{"address":"192.0.2.1","port":float64(1080)}}}},Options:&Options{MTU:1500,DNS:"https://1.1.1.1/dns-query",BypassLAN:true,SplitMode:"ExceptSelected"}} }

func TestConfigParsesWithoutStartingCore(t *testing.T) {
    for _,mode:=range []string{"ExceptSelected","OnlySelected"}{
        for _,ipv6:=range []bool{false,true}{
            r:=fixture();r.Options.SplitMode=mode;r.Options.IPv6=ipv6
            r.Options.Applications=[]string{"C:/Program Files/Browser/browser.exe"};r.Options.Domains=[]string{"example.com"};r.Options.Networks=[]string{"198.51.100.0/24"}
            document:=buildConfig(r,19090,"fixture","not-a-real-secret")
            encoded,_:=json.Marshal(document)
            // Parsing only: core.New/Start would create an adapter and are deliberately never called.
            if _,err:=serial.LoadJSONConfig(strings.NewReader(string(encoded)));err!=nil{t.Fatalf("%s ipv6=%v: %v",mode,ipv6,err)}
            rules:=document["routing"].(obj)["rules"].([]obj)
            fallback:="proxy-node";target:="direct";if mode=="OnlySelected"{fallback,target=target,fallback}
            if rules[len(rules)-1]["outboundTag"]!=fallback{t.Fatal("wrong default route")}
            for _,rule:=range rules{for _,kind:=range []string{"process","domain"}{if rule[kind]!=nil && rule["outboundTag"]!=target{t.Fatalf("wrong %s rule",kind)}}}
            if rules[0]["outboundTag"]!="proxy-node" || rules[1]["outboundTag"]!="dns-out"{t.Fatal("DNS and speed probe must precede user rules")}
            if !ipv6 && rules[2]["outboundTag"]!="block"{t.Fatal("IPv6 must not escape")}
            inbounds:=document["inbounds"].([]obj)
            if inbounds[1]["listen"]!="127.0.0.1"{t.Fatal("probe exposed to LAN")}
        }
    }
}

func TestPrivilegeBoundary(t *testing.T) {
    for _,field:=range []string{"api","log","inbounds","certificates","keyLog","dialerProxy","sockopt"}{
        r:=fixture();r.Outbound["streamSettings"]=obj{field:"C:/sensitive-file"}
        encoded,_:=json.Marshal(r)
        if _,err:=decodeRequest(encoded);err==nil{t.Fatalf("accepted %s",field)}
    }
    for _,command:=range []string{"exec","install","uninstall","run","reloadFile"}{
        r:=fixture();r.Command=command;data,_:=json.Marshal(r)
        if _,err:=decodeRequest(data);err==nil{t.Fatalf("accepted command %s",command)}
    }
    if _,err:=decodeRequest([]byte(`{"version":2,"command":"status"}`));err==nil{t.Fatal("accepted unknown version")}
    if _,err:=decodeRequest([]byte(`{"version":1,"command":"status","shell":"cmd.exe"}`));err==nil{t.Fatal("accepted unknown field")}
}

func TestInvalidOptions(t *testing.T) {
    for _,dns:=range []string{"tls://1.1.1.1","https+local://1.1.1.1/dns-query","file:///secret","https://user:password@example.com/dns"}{
        r:=fixture();r.Options.DNS=dns;if validateOptions(*r.Options)==nil{t.Fatalf("accepted resolver %s",dns)}
    }
    r:=fixture();r.Options.MTU=0;if validateOptions(*r.Options)==nil{t.Fatal("accepted MTU")}
    r=fixture();r.Options.Networks=[]string{"192.0.2.1/99"};if validateOptions(*r.Options)==nil{t.Fatal("accepted CIDR")}
    r=fixture();r.Options.Applications=[]string{"relative.exe"};if validateOptions(*r.Options)==nil{t.Fatal("accepted relative application")}
}
