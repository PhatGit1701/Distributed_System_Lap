# Lap 08 - Distributed Gossip & Quorum Simulator

Thực hiện **ba slide 36-38 về Gossip/Quorum**:

- **5 replica server process S1-S5** giao tiếp TCP.
- Gifford quorum cấu hình N_R, N_W.
- Push-Pull Anti-Entropy với peer ngẫu nhiên, mặc định mỗi **2 giây**.
- Tombstone (death certificate) cho key đã xóa.

Go 1.22+, chỉ thư viện chuẩn. Đây là năm **OS process thật**, không chỉ
đổi tên năm goroutine thành “process”.

## 1. Chạy

Từ thư mục gốc:

```powershell
cd Lap_08
go run .
```

Process chính tự sinh năm process con từ cùng executable. Mỗi replica mở
TCP localhost trên cổng do OS cấp, báo READY cho parent. Console in ID,
PID và địa chỉ socket để kiểm chứng. Không phải mở năm terminal thủ công.

```powershell
go run . -nr=3 -nw=3 -interval=2s -log=trace.log
go build -o simulator.exe .
.\simulator.exe
go test -v ./...
go vet ./...
go test -race ./...
```

Trên Linux/macOS dùng `go build -o simulator .` rồi `./simulator`.
Race detector cần môi trường Go/C compiler tương thích.
Mỗi lần chạy ghi đè file trace. Chạy `go run .`, không chỉ `go run main.go`.

Các cấu hình hợp lệ khác:

```powershell
go run . -nr=2 -nw=4
go run . -nr=1 -nw=5
```

## 2. Demo thực hiện gì?

1. Khởi động năm process và gửi CONFIG membership qua TCP.
2. Tạm dừng gossip để tạo divergence có kiểm soát.
3. Client ghi user1=Bob với N_W=3: S1,S2,S3 ACK version 1.
4. In trạng thái: S4,S5 vẫn version 0.
5. S3 chủ động Push-Pull với S4; S4 nhận Bob. Sau đó S4-S5 đồng bộ.
6. Client xóa user1: ghi tombstone version 2 tới write quorum.
7. S5 có bản Bob cũ thử đồng bộ với S1. Tombstone mới thắng; Bob không sống lại.
8. Bật gossip định kỳ 2 giây. Replica chọn peer ngẫu nhiên để Push-Pull.
9. Poll để kiểm tra cả năm có cùng tombstone; đọc quorum phải trả trạng thái deleted.
10. Dừng sạch các process con và in PASS.

Khi N_W=4 hoặc 5, số replica stale sau ghi đầu khác ví dụ N_W=3.
Thứ tự log gossip thay đổi do lịch process, nhưng điều kiện kiểm chứng giữ nguyên.

## 3. Gifford quorum là gì?

Mỗi replica có một vote, tổng N=5:

- **N_R**: số phản hồi thành công cần cho đọc.
- **N_W**: số ACK lưu đúng entry cần cho ghi.

Các điều kiện bắt buộc:

```text
1 <= N_R, N_W <= 5
N_R + N_W > 5
2 * N_W > 5
```

Điều kiện đầu về giao nhau đọc/ghi bảo đảm mỗi read quorum gặp ít nhất
một thành viên của write quorum thành công trước đó.
Điều kiện write/write buộc hai write quorum giao nhau.

Ví dụ N_R=3, N_W=3: mọi nhóm ba node đều giao nhau với nhóm ba node đã ACK.
N_R=2, N_W=2 không hợp lệ vì có thể đọc và ghi ở hai nhóm tách biệt.

`validateQuorum()` từ chối cấu hình sai trước khi khởi động demo.
Không dùng số replica đã trả lời bất kỳ để bỏ qua ngưỡng cấu hình.

## 4. Dữ liệu và phiên bản

State mỗi replica:

```text
key -> { value, version, is_tombstone }
```

Trong Go là `map[string]Entry`.

```json
{"value":"Bob","version":1,"is_tombstone":false}
```

Sau xóa:

```json
{"value":"","version":2,"is_tombstone":true}
```

Version 0 nghĩa là replica chưa có entry. Entry được ghi phải version > 0.
Tombstone không chứa giá trị cũ. Hai payload khác nhau cùng một version là
xung đột và bị báo lỗi, không tùy ý chọn một giá trị.

Project dùng **một proxy ghi**, mutex tuần tự hóa các thao tác của nó.
Trước ghi, proxy đọc quorum để lấy version cao nhất rồi tăng 1.
Không dùng thời gian thực của máy làm version.

Giới hạn này cần được hiểu rõ: demo không giải quyết nhiều writer độc lập
tự phát sinh cùng version, không phải một triển khai consensus production.

## 5. Đọc và ghi quorum trong code

### Đọc

`Quorum.read()` hỏi các replica theo danh sách:

1. Lỗi mạng không tính là vote đọc.
2. Phản hồi key chưa tồn tại vẫn là phản hồi thành công, version 0.
3. Thu đủ N_R thì trả entry có version cao nhất trong nhóm đó.
4. Không đủ N_R thì trả lỗi, không hạ ngưỡng.

Ví dụ phản hồi [0,0,1] phải chọn version 1, dù hai replica đầu chưa có Bob.
Nếu entry cao nhất là tombstone, kết quả là **deleted**, không trả một value
cũ từ replica khác. Không tìm “version cao nhất còn chưa bị xóa”.

### Ghi

`Quorum.Write()`:

1. Hỏi read quorum lấy version hiện tại.
2. Tạo entry version+1 (hoặc tombstone nếu deleted=true).
3. Gửi PUT lần lượt đến các replica.
4. Chỉ tính ACK khi replica trả Accepted=true và entry đúng payload/version.
5. Đủ N_W thì thành công; các replica khác có thể vẫn stale.

PUT version thấp hơn state hiện tại không được đếm thành ACK lưu bản ghi mới.
Do single proxy, các lần ghi thành công tạo chuỗi version tăng.

**Không đủ write quorum không có nghĩa là rollback.** Một số replica có
thể đã nhận PUT; lỗi trả về nói rõ “partial outcome may be visible”.
Đây là quorum KV, không phải giao dịch 2PC. Sau lỗi ghi không rõ kết quả,
demo không tuyên bố việc cấp version cho các writer độc lập vẫn an toàn.

## 6. Push-Pull Anti-Entropy

Một lần S3 đồng bộ S4 có hai chiều:

```text
S3 snapshot -> EXCHANGE -> S4
                           |
                      merge theo version
                           |
S3 merge   <- full state <- S4
```

**Push:** S3 gửi state của nó. S4 nhập những entry mới hơn.
**Pull:** S4 trả toàn bộ state sau merge. S3 nhập những entry nó thiếu.

Vì vậy nếu S3 có key A và S4 có key B, sau một lần exchange cả hai có A và B.
Không chỉ copy state từ source sang destination hoặc thay toàn bộ map.

`Replica.merge()` kiểm tra trên bản sao map rồi mới thay state. Một batch
có payload xung đột không được áp dụng dở một nửa.

Mỗi process có goroutine gossip riêng. Mặc định mỗi 2 giây:

1. Kiểm tra membership và trạng thái gossip enabled.
2. Chọn ngẫu nhiên một trong bốn peer khác.
3. Gọi exchange qua TCP.
4. Lỗi một lần được ghi log; chu kỳ tiếp theo vẫn tiếp tục.

Seed PRNG theo ID giúp việc kiểm thử lặp lại dễ hơn, không mang ý nghĩa bảo mật.
Đây là full-state anti-entropy, chưa tối ưu digest/delta theo kích thước dữ liệu.

## 7. Tombstone ngăn hồi sinh dữ liệu

Nếu xóa bằng cách xóa hẳn key khỏi map, một replica offline còn Bob có thể
gửi lại Bob khi đồng bộ. Node khác tưởng đó là dữ liệu nó đang thiếu và
khôi phục giá trị đã bị xóa.

Do đó xóa là **ghi một entry có version mới hơn với is_tombstone=true**.

```text
S1: tombstone v2
S5: Bob v1
Push-Pull:
    v2 > v1 -> cả hai giữ tombstone v2
```

Entry tombstone cũng được gửi trong snapshot và EXCHANGE như entry thường.
Code không bỏ tombstone khi đọc map để gossip.

Tombstone được giữ suốt vòng đời process, không tự GC theo thời gian. Chưa
có cơ chế chứng minh mọi replica đã thấy xóa nên tự bỏ sớm sẽ không an toàn.

Một write mới có chủ ý version 3 vẫn có thể tạo lại key; đó không phải “hồi
sinh” do dữ liệu version 1 cũ quay lại.

## 8. Cách đọc từng file

| File | Nội dung |
|---|---|
| main.go | Chọn chế độ demo/replica, chạy Bob -> delete -> hội tụ |
| processes.go | Sinh năm process, nhận READY, CONFIG, SYNC, enable gossip, đợi hội tụ, đóng process |
| replica.go | Entry, merge, handler TCP, exchange, goroutine gossip và replica subcommand |
| quorum.go | Điều kiện Gifford, đọc N_R, ghi đủ N_W |
| network.go | TCP JSON request/response, deadline, quản lý connection |
| quorum_test.go | Kiểm thử quorum, tombstone, hai chiều push-pull và process thật |
| DESIGN.md | Báo cáo thiết kế và ánh xạ rubric |

`runReplica()` là entrypoint của process con. Parent chạy cùng executable
với subcommand `replica -id ... -interval ...`. Nó không truy cập trực tiếp
map của process con.

READY đi qua stdout chỉ để khám phá địa chỉ TCP ban đầu; CONFIG, PUT, GET,
EXCHANGE, SYNC và điều khiển gossip đều đi qua socket.

## 9. Đồng bộ và dừng

Replica mutex bảo vệ map và membership; snapshot trả map copy để network
encoder không đọc map đang thay đổi. Không giữ mutex map trong lúc chờ TCP.

Gossip có mutex riêng cho phép parent tạm dừng và chờ exchange đang chạy
kết thúc trước khi dừng peer. Điều này giúp shutdown không tạo lỗi mạng giả.

Process parent đọc stdout của child, nối các trace bằng logger có mutex.
Close yêu cầu dừng gossip, gửi STOP, chờ process thoát. Nếu child không
thoát trong hạn, parent thu hồi riêng process nó đã tạo.

## 10. Kiểm thử

- Demo có đúng năm PID process con.
- Mọi cặp N_R/N_W thỏa Gifford đều đọc được write quorum dù đọc từ cuối danh sách.
- Cấu hình thiếu giao nhau bị từ chối.
- Push-Pull chuyển dữ liệu cả hai chiều.
- Tombstone thắng bản cũ, nhưng write mới hơn vẫn có thể tạo lại key.
- Thiếu read quorum hoặc write quorum trả lỗi, không giả báo thành công.
- Partial write được phản ánh đúng, không giả rollback.
- Xung đột cùng version bị từ chối và batch không bị áp dụng nửa chừng.
- Demo với N_R=2/N_W=4 và N_R=1/N_W=5.

TestMain trong test binary hỗ trợ cùng subcommand replica, nên integration
test cũng tạo OS process thật. Khi chạy race detector, child được sinh từ
test executable có race instrumentation.

## 11. Giới hạn

State ở RAM, không có WAL hoặc phục hồi dữ liệu khi restart process.
Membership cố định, peer đáng tin cậy, chưa có xác thực, tombstone GC,
read-repair tự động hoặc WAL compaction. Read chỉ trả max trong **read quorum**,
không quét tất cả replica để tuyên bố luôn có bản mới nhất toàn mạng.

Điều kiện quorum dựa trên phiên bản nhất quán và successful writes trong
mô hình single writer proxy. Không tuyên bố linearizability cho nhiều writer,
recovery mất dữ liệu hoặc mọi lịch network partition.
