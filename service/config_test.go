package main

import (
    "context"
    "crypto/sha256"
    "encoding/hex"
    "encoding/json"
    "net/http"
    "net/http/httptest"
    "os"
    "path/filepath"
    "slices"
    "strings"
    "testing"

    "github.com/xtls/xray-core/common/geodata"
    "github.com/xtls/xray-core/infra/conf/serial"
    "google.golang.org/protobuf/proto"
)

func TestJVMRequestsAgainstPinnedCore(t *testing.T) {
    files,err:=filepath.Glob("../logic/build/service-requests/*.json");if err!=nil{t.Fatal(err)}
    if len(files)<100{t.Fatal("Run :logic:test first to generate the JVM request matrix")}
    for _,file:=range files{t.Run(filepath.Base(file),func(t *testing.T){
        data,err:=os.ReadFile(file);if err!=nil{t.Fatal(err)}
        r,err:=decodeRequest(data);if err!=nil{t.Fatal(err)}
        encoded,_:=json.Marshal(buildConfig(r,19090,"test-user","test-password",false))
        if _,err:=serial.LoadJSONConfig(strings.NewReader(string(encoded)));err!=nil{t.Fatal(err)}
    })}
}

func fixture() Request { return Request{Version:1,Command:"connect",NodeID:"fixture",Outbound:obj{"protocol":"socks","settings":obj{"servers":[]any{obj{"address":"192.0.2.1","port":float64(1080)}}}},Options:&Options{MTU:1500,DNS:"https://1.1.1.1/dns-query",BypassLAN:true,SplitMode:"ExceptSelected"}} }

/*
Databases of the right shape, holding one entry each — enough for the core to resolve every geo
reference the document makes, which it does while parsing. Written with the core's own types, so a
change of format in the core is a failure here rather than on somebody's machine.
*/
func fakeGeo(t *testing.T) string {
    t.Helper()
    dir:=t.TempDir()
    sites,err:=proto.Marshal(&geodata.GeoSiteList{Entry:[]*geodata.GeoSite{{Code:"CATEGORY-RU",Domain:[]*geodata.Domain{{Type:geodata.Domain_Domain,Value:"ru"}}}}})
    if err!=nil{t.Fatal(err)}
    ips,err:=proto.Marshal(&geodata.GeoIPList{Entry:[]*geodata.GeoIP{{Code:"RU",Cidr:[]*geodata.CIDR{{Ip:[]byte{5,8,0,0},Prefix:16}}}}})
    if err!=nil{t.Fatal(err)}
    if os.WriteFile(filepath.Join(dir,"geosite.dat"),sites,0o644)!=nil || os.WriteFile(filepath.Join(dir,"geoip.dat"),ips,0o644)!=nil{t.Fatal("write")}
    return dir
}

func parse(t *testing.T, document obj) {
    t.Helper()
    encoded,_:=json.Marshal(document)
    // Parsing only: core.New/Start would create an adapter and are deliberately never called.
    if _,err:=serial.LoadJSONConfig(strings.NewReader(string(encoded)));err!=nil{t.Fatal(err)}
}

func outbound(rule obj) string { tag,_:=rule["outboundTag"].(string);return tag }

func TestConfigParsesWithoutStartingCore(t *testing.T) {
    t.Setenv("XRAY_LOCATION_ASSET",fakeGeo(t))
    for _,mode:=range []string{"ExceptSelected","OnlySelected"}{
        for _,ipv6:=range []bool{false,true}{
            for _,geo:=range []bool{false,true}{
                r:=fixture();r.Options.SplitMode=mode;r.Options.IPv6=ipv6
                r.Options.Applications=[]string{"C:/Program Files/Browser/browser.exe","Telegram.exe"};r.Options.Domains=[]string{"example.com"};r.Options.Networks=[]string{"198.51.100.0/24"}
                document:=buildConfig(r,19090,"fixture","not-a-real-secret",geo)
                parse(t,document)
                rules:=document["routing"].(obj)["rules"].([]obj)
                fallback:="proxy-node";target:="direct";if mode=="OnlySelected"{fallback,target=target,fallback}
                if outbound(rules[len(rules)-1])!=fallback{t.Fatal("wrong default route")}
                for _,rule:=range rules{
                    if rule["process"]!=nil && rule["domain"]==nil && outbound(rule)!=target{t.Fatal("wrong application rule")}
                    if d,ok:=rule["domain"].([]string);ok && slices.Contains(d,"domain:example.com") && outbound(rule)!=target{t.Fatal("wrong domain rule")}
                }
                if outbound(rules[0])!="proxy-node" || outbound(rules[1])!="dns-out"{t.Fatal("DNS and speed probe must precede user rules")}
                if outbound(rules[2])!="block"{t.Fatal("the tunnel's own subnet must be dropped before anything can send it round")}
                if !ipv6 && outbound(rules[3])!="block"{t.Fatal("IPv6 must not escape")}
                inbounds:=document["inbounds"].([]obj)
                if inbounds[1]["listen"]!="127.0.0.1"{t.Fatal("probe exposed to LAN")}
                encoded,_:=json.Marshal(document)
                if geo!=strings.Contains(string(encoded),"geosite:"){t.Fatal("geo references must follow the flag")}
            }
        }
    }
}

// The order the whole feature is about: a browser sent wholly through the tunnel still opens
// Russian sites directly, and the user's own domain list still beats the databases.
func TestRussianSitesBypassApplicationRules(t *testing.T) {
    t.Setenv("XRAY_LOCATION_ASSET",fakeGeo(t))
    index:=func(rules []obj, match func(obj) bool) int { for i,r:=range rules{if match(r){return i}};return -1 }
    isGeo:=func(r obj) bool { d,_:=r["domain"].([]string);return slices.Contains(d,"geosite:category-ru") }
    isApps:=func(r obj) bool { return r["process"]!=nil && r["domain"]==nil }
    isUser:=func(r obj) bool { d,_:=r["domain"].([]string);return slices.Contains(d,"domain:example.ru") }
    isYouTube:=func(r obj) bool { d,_:=r["domain"].([]string);return slices.Contains(d,"domain:googlevideo.com") }

    r:=fixture();r.Options.SplitMode="OnlySelected";r.Options.Applications=[]string{"chrome.exe"};r.Options.Domains=[]string{"example.ru"}
    rules:=buildConfig(r,1,"u","p",true)["routing"].(obj)["rules"].([]obj)
    if !(index(rules,isUser)<index(rules,isGeo) && index(rules,isGeo)<index(rules,isApps)){t.Fatal("only-selected: user domains, then databases, then applications")}
    yt:=rules[index(rules,isYouTube)]
    if yt["process"]==nil || outbound(yt)!="proxy-node"{t.Fatal("only-selected: YouTube exception belongs to the chosen applications alone")}

    r=fixture();r.Options.SplitMode="ExceptSelected";r.Options.Applications=[]string{"chrome.exe"}
    rules=buildConfig(r,1,"u","p",true)["routing"].(obj)["rules"].([]obj)
    if !(index(rules,isApps)<index(rules,isYouTube) && index(rules,isYouTube)<index(rules,isGeo)){t.Fatal("except-selected: applications around the tunnel before the YouTube exception and the databases")}
    if outbound(rules[index(rules,isGeo)])!="direct"{t.Fatal("databases send Russian sites direct")}
}

func TestApplicationNames(t *testing.T) {
    for _,ok:=range []string{"chrome.exe","Telegram.exe","Discord","C:/Program Files/App/app.exe",`C:\Program Files\App\app.exe`}{
        if !validApplication(ok){t.Fatalf("refused %q",ok)}
    }
    for _,bad:=range []string{"","..","a/b.exe","relative/app.exe"," chrome.exe","chrome.exe\n","con:","x?.exe"}{
        if validApplication(bad){t.Fatalf("accepted %q",bad)}
    }
    got:=applicationVariants([]string{"Telegram.exe","chrome.exe",`C:\Apps\Tool.exe`})
    for _,want:=range []string{"Telegram","telegram","chrome","Chrome","C:/Apps/Tool.exe"}{
        if !slices.Contains(got,want){t.Fatalf("missing %q in %v",want,got)}
    }
    for _,v:=range got{if strings.HasSuffix(v,".exe") && !strings.Contains(v,"/"){t.Fatalf("the core compares names without .exe: %q",v)}}
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
    // The database update names nothing: no URL and no path can ride along with it.
    if _,err:=decodeRequest([]byte(`{"version":1,"command":"geo_update"}`));err!=nil{t.Fatal("refused the database update")}
    if _,err:=decodeRequest([]byte(`{"version":1,"command":"geo_update","url":"https://example.com/x.dat"}`));err==nil{t.Fatal("accepted a URL for the database update")}
}

func TestInvalidOptions(t *testing.T) {
    for _,dns:=range []string{"tls://1.1.1.1","https+local://1.1.1.1/dns-query","file:///secret","https://user:password@example.com/dns"}{
        r:=fixture();r.Options.DNS=dns;if validateOptions(*r.Options)==nil{t.Fatalf("accepted resolver %s",dns)}
    }
    r:=fixture();r.Options.MTU=0;if validateOptions(*r.Options)==nil{t.Fatal("accepted MTU")}
    r=fixture();r.Options.Networks=[]string{"192.0.2.1/99"};if validateOptions(*r.Options)==nil{t.Fatal("accepted CIDR")}
    r=fixture();r.Options.Applications=[]string{"relative/app.exe"};if validateOptions(*r.Options)==nil{t.Fatal("accepted relative application path")}
}

func TestGeoDownloadVerifiesAndReplacesAtomically(t *testing.T) {
    body:=strings.Repeat("x",geoMinimumBytes+10)
    sum:=sha256.Sum256([]byte(body))
    corrupt:=false
    server:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,r *http.Request){
        switch r.URL.Path {
        case "/db.dat": if corrupt{_,_=w.Write([]byte(body[:len(body)-1]+"y"))}else{_,_=w.Write([]byte(body))}
        case "/db.dat.sha256sum": _,_=w.Write([]byte(hex.EncodeToString(sum[:])+"  db.dat\n"))
        case "/small.dat": _,_=w.Write([]byte("<html>blocked</html>"))
        default: http.NotFound(w,r)
        }
    }))
    defer server.Close()
    dir:=t.TempDir()
    asset:=[]geoAsset{{"geoip.dat",server.URL+"/db.dat"}}
    if err:=downloadGeo(context.Background(),server.Client(),dir,asset);err!=nil{t.Fatal(err)}
    if data,_:=os.ReadFile(filepath.Join(dir,"geoip.dat"));string(data)!=body{t.Fatal("wrong bytes on disk")}

    corrupt=true
    if downloadGeo(context.Background(),server.Client(),dir,asset)==nil{t.Fatal("accepted a checksum mismatch")}
    if data,_:=os.ReadFile(filepath.Join(dir,"geoip.dat"));string(data)!=body{t.Fatal("a failed download replaced the good database")}
    if _,err:=os.Stat(filepath.Join(dir,"geoip.dat.part"));err==nil{t.Fatal("left a partial file behind")}

    if downloadGeo(context.Background(),server.Client(),dir,[]geoAsset{{"geosite.dat",server.URL+"/small.dat"}})==nil{t.Fatal("accepted an error page as a database")}
    if inspectGeo(dir).Ready{t.Fatal("ready with one database missing")}
}
