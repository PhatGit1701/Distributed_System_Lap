# Lab 05 - Two-Phase Commit (2PC) Simulator bằng Go

Project mô phỏng **1 coordinator socket server và 3 participant socket server**,
giao tiếp qua **TCP + JSON**. Các thay đổi trạng thái được ghi vào WAL trên ổ
đĩa và gọi `File.Sync()` trước khi gửi phiếu, quyết định hoặc ACK tương ứng.

Mặc định chương trình chạy ba kịch bản:

| Transaction | Tình huống | Kết quả được kiểm tra |
|---|---|---|
| 101 | P3 bỏ phiếu ABORT do tiêm lỗi lock conflict | Cả ba GLOBAL_ABORT |
| 102 | Cả ba bỏ phiếu COMMIT | Cả ba GLOBAL_COMMIT |
| 103 | P2 dừng sau khi ghi YES, trước khi nhận quyết định | P2 reboot từ WAL, hỏi coordinator và hoàn thành COMMIT |

Bạn nên **chạy thử**, đọc **phần 3-7** để hiểu nguyên lý, rồi đọc **phần 8-10**
khi mở code.

## 1. Cách chạy

Yêu cầu Go 1.22 trở lên. Chỉ dùng thư viện chuẩn Go, không cần database,
Docker hay package ngoài.

Từ thư mục gốc repository:

```powershell
cd Lap_05
go run .
```

Chương trình in trace và lưu cùng nội dung vào `trace.log`. Mỗi lần chạy demo
tạo một thư mục WAL riêng trong `data/run-...`; đường dẫn được in ra console.
Việc này giữ lại log cũ, tránh tái sử dụng transaction ID của lần demo trước.

Biên dịch trên Windows:

```powershell
go build -o two-phase-commit.exe .
.\two-phase-commit.exe
```

Trên Linux/macOS:

```bash
go build -o two-phase-commit .
./two-phase-commit
```

Thay đường dẫn dữ liệu hoặc file trace:

```powershell
go run . -data data -log trace.log
```

File trace đã chọn bị ghi đè khi chạy. **WAL của các lần demo trước không bị xóa.**
`go run .` nạp tất cả file trong package; `go run main.go` không nạp các file
còn lại nên sẽ báo thiếu hàm.

### Khởi động lại từ log đã có

Lấy đường dẫn thật từ dòng `Stable WAL directory`, ví dụ:

```powershell
go run . -resume data/run-123456789 -log recovery-trace.log
```

Đây là ví dụ; thay tên thư mục bằng tên của lần chạy của bạn. Chương trình sẽ
khởi động lại cả bốn server từ WAL, phục hồi các transaction đang chờ và gửi lại
các quyết định đã có. Không tạo transaction demo mới trong chế độ resume.

Không chạy đồng thời hai chương trình trên **cùng một thư mục WAL**.
Demo mới có thư mục riêng; chế độ resume giả định chương trình cũ đã dừng.

## 2. Cấu trúc thư mục

```text
Lap_05/
  go.mod                 Module Go, không có dependency ngoài
  main.go                CLI, chọn demo/resume, mở console trace
  protocol.go            Message JSON và các hằng giao thức
  transport.go           TCP request/response và socket server chung
  wal.go                 Đọc, append, Sync và phục hồi đuôi WAL bị ghi dở
  coordinator.go         Hai pha, quyết định bền vững và gửi lại quyết định
  participant.go         Bỏ phiếu, giữ khóa, commit/rollback và recovery
  simulation.go          Dựng 4 server, chạy ba kịch bản và kiểm tra kết quả
  lab_test.go            Kiểm thử giao thức, WAL và reboot
  README.md              Tài liệu giải thích này
  trace.log              Trace chạy demo thực tế
  recovery-trace.log     Trace chạy lại từ WAL mẫu
  examples/wal/          Bản sao WAL mẫu sau demo để đọc và thử resume
  data/run-.../          WAL mỗi lần chạy tại máy bạn (Git ignore)
```

Các file `.exe` và dữ liệu runtime trong `data/` được ignore. Các WAL trong
`examples/wal/` là dữ liệu mô phỏng, được đưa vào Git để đọc cùng trace.

## 3. 2PC giải quyết điều gì?

Một transaction phân tán cần thay đổi tại nhiều participant. Nếu chỉ gửi
“commit” lần lượt thì có thể P1 đã thực hiện, còn P2 không thể thực hiện:
các bên không còn thống nhất kết quả.

2PC tách quá trình thành **chuẩn bị** và **quyết định**:

1. Coordinator hỏi tất cả participant: “Bạn có sẵn sàng commit transaction này không?”
2. Chỉ khi **mọi participant đều trả YES**, coordinator mới chọn GLOBAL_COMMIT.
3. Chỉ một NO, lỗi mạng, timeout hoặc phản hồi không hợp lệ cũng khiến coordinator
   chọn GLOBAL_ABORT khi chưa có quyết định.
4. Coordinator ghi quyết định bền vững rồi thông báo cho tất cả.

Điểm khó: sau khi participant đã trả YES, nó đã cam kết sẽ làm theo quyết định
sau này. Nó **không được tự ABORT chỉ vì chờ lâu**.

Lab mô phỏng commit/rollback bằng **trạng thái transaction trong WAL** và một
khóa tài nguyên ở participant. Không có database, số dư hay câu SQL; phần cần
kiểm chứng là giao thức quyết định và phục hồi, không phải nghiệp vụ dữ liệu.

## 4. Kiến trúc và thông điệp

Mỗi server có TCP listener riêng trên `127.0.0.1`. Cổng 0 trong
`net.Listen("tcp", "127.0.0.1:0")` yêu cầu hệ điều hành cấp một cổng trống.
Địa chỉ thật được in đầu trace.

```text
                  Coordinator
            START / GLOBAL_* -> coordinator.wal
                  /    |    \
                 TCP  TCP   TCP
                  /    |      \
                P1     P2      P3
                 |      |       |
           participant-1.wal    participant-3.wal
                   participant-2.wal
```

Coordinator thực sự có socket server: nó nhận `GET_DECISION` để participant
reboot hỏi kết quả. Hàm Execute được bộ mô phỏng gọi trực tiếp để bắt đầu
transaction; coordinator sau đó liên lạc với participant qua TCP.

| Message | Người gửi -> người nhận | Ý nghĩa |
|---|---|---|
| VOTE_REQUEST | Coordinator -> participant | Yêu cầu chuẩn bị transaction |
| VOTE_COMMIT | Participant -> coordinator | YES: đã chuẩn bị bền vững |
| VOTE_ABORT | Participant -> coordinator | NO: không thể thực hiện |
| GLOBAL_COMMIT | Coordinator -> participant | Quyết định cuối: commit |
| GLOBAL_ABORT | Coordinator -> participant | Quyết định cuối: rollback |
| ACK | Participant -> coordinator | Đã lưu quyết định và áp dụng trạng thái |
| GET_DECISION | Participant reboot -> coordinator | Hỏi quyết định bền vững |
| UNKNOWN | Coordinator -> participant reboot | Chưa có quyết định được công bố |
| ERROR | Bên nhận -> bên gửi | Request không hợp lệ hoặc lỗi lưu trữ |

Ví dụ request:

```json
{"type":"VOTE_REQUEST","tx_id":101}
```

Mỗi kết nối TCP mang một request và một response rồi đóng. Client có hạn kết
nối 800 ms và deadline trao đổi 800 ms sau khi kết nối. Các request multicast
chạy song song bằng goroutine. Một peer chậm không ngăn coordinator hỏi peer khác.

## 5. Pha 1 - Prepare / Voting

### Coordinator

Trong `Coordinator.Execute()`:

1. Kiểm tra transaction ID dương và chưa có lỗi WAL.
2. Ghi `START_2PC` kèm ID ba participant, gọi Sync.
3. Multicast VOTE_REQUEST tới P1, P2, P3.
4. Thu đủ kết quả request, mỗi request đều có deadline.

### Participant

Trong `Participant.vote()`:

1. Nếu transaction đã có trạng thái, trả lại vote tương ứng, không ghi lại.
2. Nếu tài nguyên đang bị transaction khác giữ hoặc có tiêm lỗi, chọn VOTE_ABORT.
3. Ngược lại, chọn VOTE_COMMIT.
4. Append vote vào WAL và Sync.
5. Cập nhật trạng thái bộ nhớ; nếu YES thì giữ khóa cho transaction.
6. Mới gửi vote về coordinator.

`VOTE_COMMIT` trong WAL tương ứng với trạng thái **PREPARED**, chưa phải đã
commit. Nó chỉ nghĩa là “tôi đã chuẩn bị và hứa chờ quyết định cuối”.

Một participant mô phỏng một tài nguyên độc quyền. `lockOwner = tx_id` nghĩa
là tài nguyên đó đang được transaction giữ. Khóa phải được giữ từ lúc YES
đến khi nhận quyết định cuối, kể cả khi reboot.

## 6. Pha 2 - Quyết định toàn cục

Coordinator chọn:

```text
Mọi phản hồi đều VOTE_COMMIT đúng tx_id -> GLOBAL_COMMIT
Bất cứ phản hồi nào khác              -> GLOBAL_ABORT
```

Trước khi gửi quyết định, `saveDecision()` bắt buộc ghi quyết định và Sync.
Khi đã có GLOBAL_COMMIT bền vững, coordinator **không được đổi thành ABORT**,
dù một participant không trả ACK.

Participant xử lý quyết định trong `decide()`:

1. Nếu quyết định giống trạng thái cuối đã có, trả ACK ngay, không áp dụng lần hai.
2. Nếu trái với quyết định cuối, trả ERROR.
3. Nếu nhận COMMIT nhưng chưa có YES bền vững, trả ERROR.
4. Ghi quyết định vào WAL và Sync.
5. Cập nhật trạng thái, giải phóng khóa rồi trả ACK.

Participant chưa từng vote vẫn có thể chấp nhận GLOBAL_ABORT. Ví dụ node vắng
mặt từ đầu nên không nhận VOTE_REQUEST; sau reboot, quyết định ABORT vẫn an toàn.
Ngược lại, GLOBAL_COMMIT luôn đòi hỏi đã có VOTE_COMMIT.

### Vì sao thiếu ACK không phải là một phiếu NO?

Phiếu ở pha 1 dùng để **chọn** quyết định. ACK ở pha 2 chỉ xác nhận đã nhận và
thực hiện quyết định **đã chọn**.

Nếu P2 mất kết nối sau khi coordinator đã ghi GLOBAL_COMMIT:

- P1/P3 có thể đã commit.
- Đổi thành ABORT sẽ gây bất nhất.
- Phải giữ GLOBAL_COMMIT, ghi trace thiếu ACK và gửi lại sau khi P2 hồi phục.

`Outcome.Decision` và `Outcome.AllACK()` là hai thông tin riêng.

## 7. Ba kịch bản trong simulation.go

### Tx 101 - P3 bỏ phiếu ABORT

`InjectAbort(101)` đặt cờ để P3 mô phỏng lock conflict cho transaction này.
Cờ chỉ ảnh hưởng việc chọn vote; không sửa WAL hoặc kết quả của coordinator.

```text
COORD -> Log START_2PC -> Sync -> gửi VOTE_REQUEST
P1    -> Log VOTE_COMMIT -> Sync -> YES
P2    -> Log VOTE_COMMIT -> Sync -> YES
P3    -> Lock Conflict -> Log VOTE_ABORT -> Sync -> NO
COORD -> Log GLOBAL_ABORT -> Sync -> thông báo tất cả
P1-P3 -> Log GLOBAL_ABORT -> Sync -> rollback -> ACK
SYSTEM -> Tx 101 Aborted Cleanly.
```

### Tx 102 - Tất cả YES

Khóa của Tx 101 đã được giải phóng sau ABORT. Cả ba participant ghi YES,
coordinator ghi GLOBAL_COMMIT và tất cả ghi COMMIT, ACK. Bộ mô phỏng kiểm tra
trạng thái từng participant trước khi in Committed Cleanly.

### Tx 103 - P2 reboot khi PREPARED

1. Cả ba participant ghi VOTE_COMMIT và gửi YES.
2. Coordinator ghi GLOBAL_COMMIT, Sync.
3. Điểm tiêm lỗi `beforeBroadcast` đóng P2 trước lúc phát quyết định.
4. P1/P3 nhận COMMIT; coordinator không có ACK của P2.
5. Tạo **một đối tượng P2 mới** trên cùng file WAL và cổng cũ.
6. P2 đọc WAL, thấy Tx 103 mới có VOTE_COMMIT, phục hồi khóa.
7. P2 gửi GET_DECISION qua TCP tới coordinator.
8. Coordinator trả GLOBAL_COMMIT từ trạng thái đã replay/lưu bền.
9. P2 ghi GLOBAL_COMMIT, Sync và hoàn thành commit.
10. Coordinator gửi lại decision để thu đủ ACK. Các node trả ACK cho duplicate,
    không ghi thêm một lần commit.

Đây là mô phỏng crash-stop một server/goroutine rồi reboot đối tượng, không
phải giết cả chương trình OS. File WAL thật được đóng và mở lại; recovery
không sao chép trạng thái của đối tượng P2 cũ sang đối tượng mới.

## 8. WAL và recovery hoạt động thế nào?

### Định dạng

Mỗi dòng là một JSON record; kết thúc bằng newline:

```json
{"tx_id":101,"event":"START_2PC","participants":[1,2,3]}
{"tx_id":101,"event":"GLOBAL_ABORT"}
```

Đó là ví dụ WAL coordinator. WAL participant có thể là:

```json
{"tx_id":103,"event":"VOTE_COMMIT"}
{"tx_id":103,"event":"GLOBAL_COMMIT"}
```

Code không chỉ ghi console rồi gọi đó là stable log. `WAL.Append()` thực hiện:

```text
Marshal JSON -> append newline -> file.Write -> kiểm tra short write -> file.Sync
```

Chỉ sau thành công mới sửa trạng thái bộ nhớ và cho phép reply.
Nếu Write/Sync lỗi, WAL chuyển sang trạng thái lỗi và không cho ghi tiếp.
Participant không trả YES/ACK thành công; coordinator không phát quyết định
chưa lưu bền. Cần dừng và recovery để xác định lại phần đã được lưu.

Các file được tạo trong thư mục local, tồn tại sau khi server dừng. Mô hình
giả định một writer cho mỗi WAL và storage thực hiện đúng Sync. Đây là lab
phục hồi process; không mô phỏng hỏng đĩa, checksum hay mọi dạng mất điện/
metadata thư mục của filesystem.

### Đuôi file ghi dở

Crash giữa một lần write có thể để lại phần cuối không có newline.
`openWAL()` cắt phần đuôi chưa hoàn chỉnh về newline cuối và Sync file.
Một dòng hoàn chỉnh nhưng JSON sai phải báo lỗi; không âm thầm bỏ qua để
tránh mất một quyết định đã có.

WAL hợp lệ sau đó được kiểm tra chuyển trạng thái theo vai trò. Ví dụ
participant WAL có COMMIT mà thiếu YES là lỗi, không được phục hồi như thể
đã commit hợp lệ.

### Participant reboot

`replay()` đọc tuần tự WAL để phục hồi map trạng thái và khóa. Với transaction
chưa có quyết định, `Recover()` hỏi coordinator qua TCP.

- Có GLOBAL_COMMIT/GLOBAL_ABORT: áp dụng quyết định và Sync.
- UNKNOWN hoặc coordinator không liên lạc được: giữ nguyên trạng thái chờ.
- PREPARED vẫn giữ khóa và báo lỗi “in doubt”; không tự chọn kết quả.

Đây là **tính blocking của 2PC**. Nếu coordinator mất liên lạc thì participant
đã vote YES có thể không giải phóng tài nguyên được.
Trong demo cần gọi lại Recover hoặc chạy resume khi coordinator sẵn sàng;
không có vòng retry nền vô hạn.

### Coordinator reboot

Coordinator đọc START và các quyết định từ WAL:

| WAL coordinator | Hành động |
|---|---|
| Có GLOBAL_COMMIT | Giữ COMMIT, có thể gửi lại |
| Có GLOBAL_ABORT | Giữ ABORT, có thể gửi lại |
| Có START nhưng chưa có decision | Ghi GLOBAL_ABORT + Sync trước khi phục vụ recovery |

Việc chọn ABORT ở hàng cuối là an toàn trong mô hình này vì coordinator luôn
Sync decision **trước** khi gửi ra mạng. Không có decision bền vững nghĩa là
chưa có quyết định hợp lệ nào được phát trước crash.

Khi resume, cổng TCP có thể thay đổi. Danh tính P1-P3 vẫn cố định; WAL lưu ID,
không dùng địa chỉ socket cũ như danh tính transaction.

## 9. Đọc code theo thứ tự

### main.go

`run()` chọn demo mới hoặc resume, kiểm tra các file recovery có tồn tại,
tạo thư mục cho demo mới, mở trace rồi gọi `runDemo()`/`runRecovery()`.
Lỗi trả về main được in với exit code 1.

### simulation.go

`startLab()` tạo listener coordinator trước để participant biết địa chỉ,
khởi động ba participant, sau đó chạy coordinator với đủ địa chỉ peer.
`runDemo()` chỉ dựng tình huống và xác minh; không gán quyết định cho node.
`verify()` kiểm tra trạng thái cuối của cả ba.

### coordinator.go

| Hàm | Vai trò |
|---|---|
| startCoordinator | Mở/replay WAL, chốt ABORT cho START chưa có decision, chạy socket server |
| Execute | START, pha 1, chọn và Sync decision, pha 2 |
| multicast | Chạy request TCP tới ba peer song song, thu kết quả |
| saveDecision | Append+Sync decision trước khi cập nhật bộ nhớ |
| deliver | Phát decision, ghi ACK/missing ACK vào trace |
| handle | Phục vụ GET_DECISION qua TCP |
| Redeliver | Gửi lại tất cả decision đã biết sau reboot |

Gọi lại Execute với transaction đã quyết định chỉ **gửi lại decision cũ**,
không mở pha bỏ phiếu mới và không tái sử dụng transaction ID cho nghiệp vụ khác.
ACK không có WAL riêng: sau reboot có thể gửi lại mọi decision, nhờ idempotency.

### participant.go

| Hàm | Vai trò |
|---|---|
| startParticipant | Replay file WAL rồi mở TCP listener |
| handle | Khóa trạng thái, xác thực và điều phối message |
| vote | Kiểm tra lock/fault, ghi vote, giữ khóa và trả lời |
| decide | Kiểm tra quyết định, ghi WAL, commit/rollback, trả ACK |
| replay | Kiểm tra và phục hồi từng record |
| Recover | Hỏi coordinator cho transaction còn chưa có decision |
| Close | Ngừng nhận request, chờ handler kết thúc, đóng WAL |

### wal.go

`openWAL()` trả đối tượng WAL và danh sách record đã đọc.
`Append()` bảo vệ file bằng mutex, ghi đúng một record rồi Sync.
`poison` giữ lỗi ghi/Sync để không tiếp tục giao thức với storage mơ hồ.

### transport.go

`call()` là TCP client một request/một response.
`socketServer` nhận nhiều kết nối bằng goroutine. Khi Close, đóng listener
và các kết nối đang hoạt động rồi chờ tất cả handler kết thúc trước lúc
caller đóng WAL. Handler không còn chạy ngầm trên file đã đóng.

## 10. Đồng bộ và tính idempotent

Một participant có mutex bảo vệ transaction map, lockOwner và thao tác
kiểm tra-ghi-cập nhật. Hai VOTE_REQUEST đồng thời cho cùng tx_id không thể
ghi hai phiếu khác nhau.

Coordinator có hai mutex:

- `executeMu`: chạy tuần tự Execute trong demo; Close đợi transaction đang chạy.
- `mu`: bảo vệ trạng thái transaction; được thả trong lúc chờ mạng để recovery
  query vẫn được phục vụ.

WAL có mutex riêng để append record nguyên vẹn. Logger có mutex để giữ mỗi
dòng console không bị chen ký tự từ goroutine khác.

Mạng có thể làm mất response: người gửi không biết request đã thực hiện chưa.
Vì vậy không thể giả định request chỉ đến một lần.

- Vote lặp: trả lại vote đã biết.
- Decision lặp giống nhau: ACK, không append/apply lần hai.
- Decision trái kết quả đã chốt: ERROR, giữ kết quả cũ.
- COMMIT chưa có YES: ERROR.

Đây là xử lý lặp an toàn trong phạm vi trạng thái mô phỏng, không phải cam kết
“exactly once” cho mọi side effect database hay dịch vụ ngoài.

## 11. Kiểm thử và đối chiếu kết quả

```powershell
go test -v ./...
go vet ./...
go test -race ./...
```

Race detector cần môi trường Go/C compiler tương thích. Test thông thường
và bản demo không cần C compiler.

Các nhóm kiểm thử:

- Ba kịch bản demo, trace và WAL thật sau khi reboot.
- Thiếu vote dẫn đến ABORT; thiếu ACK không làm đổi decision đã chốt.
- PREPARED reboot vẫn giữ khóa khi coordinator trả UNKNOWN.
- Coordinator reboot giữ COMMIT đã lưu, chỉ ABORT START chưa có decision.
- Duplicate đồng thời không làm append/apply hai lần.
- Từ chối COMMIT nếu chưa vote YES hoặc đã vote NO.
- Lỗi ghi WAL không được trả YES.
- Cắt đuôi WAL ghi dở; từ chối dòng JSON hoàn chỉnh nhưng hỏng.
- Resume cả bốn server từ file; server Close không còn nhận TCP.

Đọc `examples/wal/` cùng `trace.log` để kiểm tra:

1. Coordinator có START trước GLOBAL_*.
2. Tx 101 có VOTE_ABORT tại P3 và GLOBAL_ABORT ở mọi participant.
3. Tx 102, 103 có VOTE_COMMIT trước GLOBAL_COMMIT tại mọi participant.
4. P2 Tx 103 chỉ có một record GLOBAL_COMMIT dù recovery và nhận lại decision.

Có thể thử resume từ dữ liệu mẫu:

```powershell
go run . -resume examples/wal -log recovery-trace.log
```

## 12. Phạm vi bài lab

Project đáp ứng 1 coordinator + 3 participant TCP server, ghi WAL đồng bộ,
ABORT injection và reboot recovery. Các node là goroutine/server trong một
process Go, không phải bốn process OS độc lập.

Chưa triển khai xác thực mạng, participant recovery tự động theo lịch nền,
WAL compaction, database thực hay coordinator replication. Nếu coordinator
không truy cập được, transaction PREPARED có thể bị blocking: đó là đặc điểm
2PC cần quan sát, không phải tình huống được giải quyết bằng tự ý rollback.

Không có thao tác tự xóa WAL. Muốn lưu code và ví dụ trong Git, đưa source,
README, trace và examples vào commit; dữ liệu runtime đã được ignore.
