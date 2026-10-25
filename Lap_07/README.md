# Lap 07 - Client-Centric Session Proxy

Thực hiện **hai slide 37-38 về Client-Centric Consistency**: proxy đứng giữa
client và ba replica S1, S2, S3; theo dõi session vector và bảo đảm
**Read-Your-Writes (RYW)** cùng **Monotonic Reads (MR)**.

Ngôn ngữ Go 1.22+, chỉ dùng thư viện chuẩn. Các replica có TCP socket riêng,
bộ nhớ riêng; không chia sẻ map dữ liệu. Proxy là đối tượng Go trong ứng dụng
client, mọi trao đổi với replica đều qua TCP/JSON.

## 1. Chạy và kiểm thử

Từ thư mục gốc repository:

```powershell
cd Lap_07
go run .
```

Console được lưu đồng thời vào `trace.log`. Mặc định replication nền chạy
mỗi 3 giây, nên các lần đổi replica trong demo có thể gặp dữ liệu cũ.
Proxy không đợi replication nền mà sửa replica ngay nếu session yêu cầu.

```powershell
go run . -delay=5s -log=my-trace.log
go build -o simulator.exe .
.\simulator.exe
go test -v ./...
go vet ./...
go test -race ./...
```

`go run .` nạp cả package; không dùng `go run main.go`. Race detector cần
môi trường Go/C compiler hỗ trợ. File log được ghi đè mỗi lần chạy.
Trên Linux/macOS có thể `go build -o simulator .` rồi `./simulator`.

## 2. Demo thực hiện những gì?

1. Session A ghi profile = Alice tại S1; V_write thành [1,0,0].
2. Route A sang S2 đang cũ. Proxy phát hiện S2 chưa có write của A.
3. Proxy lấy write-set S1:1 từ S1 qua TCP, áp dụng vào S2, sau đó mới đọc.
4. S2 trả Alice: Read-Your-Writes được bảo đảm.
5. Session B ghi profile = Bob tại S2; S2 đã có Alice nên Bob phụ thuộc Alice.
   Clock của S2 là [1,1,0].
6. A đọc Bob tại S2; V_read của A thành [1,1,0].
7. Route A sang S3 đang cũ. Proxy lấy đủ Alice và Bob rồi mới cho đọc.
8. A vẫn thấy Bob: Monotonic Reads được bảo đảm.

Chạy với delay rất ngắn vẫn phải đúng nhưng có thể không còn dòng sửa stale
vì replication nền đã kịp đồng bộ. Dùng mặc định để quan sát rõ trace mẫu.

## 3. Hai bảo đảm có nghĩa gì?

**Read-Your-Writes:** sau khi client ghi thành công, các lần đọc tiếp theo
trong cùng session phải được phục vụ bởi replica đã chứa write đó cùng
những phụ thuộc của nó. Client không được đọc như thể write vừa ghi chưa có.

**Monotonic Reads:** một session đã đọc trạng thái mới thì khi đổi replica,
không được quay về lịch sử cũ hơn những gì đã quan sát.

Ví dụ session chỉ đọc, chưa hề ghi: đọc Bob tại S2 rồi chuyển sang S3 chỉ có
Alice. RYW một mình không ngăn được lùi dữ liệu; cần V_read cho MR.

Một client khác có session rỗng vẫn có thể thấy replica cũ. Đây là bảo đảm
theo session, không phải mọi client luôn nhìn cùng một bản mới nhất.

## 4. Vector và write-set

Vector [a,b,c] tại replica cho biết nó đã áp dụng liên tục a write của S1,
b write của S2, c write của S3. Đây là **prefix đầy đủ**, không phải số lớn
nhất tùy ý đã từng nghe thấy.

`Vector.Covers(required)` đúng khi:

```text
clock[0] >= required[0]
clock[1] >= required[1]
clock[2] >= required[2]
```

Không dùng tổng các ô hoặc so sánh từ trái sang phải. [0,2,0] không đủ cho
[1,0,0]: dù tổng lớn hơn, write S1:1 vẫn thiếu. Hai vector có thể không so
sánh được theo quan hệ nhân quả.

Mỗi `Write` chứa:

| Trường | Ý nghĩa |
|---|---|
| Origin | 0,1,2 tương ứng S1,S2,S3 |
| Seq | Thứ tự write do origin đó tạo |
| Deps | Vector phụ thuộc ngay trước write |
| Key / Value | Dữ liệu cần áp dụng |
| ID() | Định danh ổn định, ví dụ S2:1 |

Ví dụ Alice: origin=0, seq=1, deps=[0,0,0].
Bob ghi tại S2 sau khi S2 có Alice: origin=1, seq=1, deps=[1,0,0].
Do đó Bob không được áp dụng trước Alice.

## 5. Luồng xử lý proxy

Session giữ `read` và `write`, độc lập cho từng client:

```text
required = component-wise-max(V_read, V_write)
                    |
                 STATE target
                    |
       target.Clock có Covers(required)?
            /                     \
          Có                     Không
           |                       |
      READ/WRITE         EXPORT write-sets từ S1..S3
           |                       |
    cập nhật vector          APPLY tại target
           |                       |
         trả dữ liệu       kiểm tra lại đủ vector
```

Trong `ensure()`, proxy:

1. Xác thực ID S1..S3 và kiểm tra context.
2. Hỏi STATE của replica đích.
3. Nếu chưa đủ, EXPORT các write-set thuộc prefix session cần từ các replica.
4. Bỏ write-set trùng ID; nếu cùng ID khác nội dung thì báo lỗi.
5. Gửi APPLY tới đích. Replica chỉ nhận batch có đủ phụ thuộc.
6. Chỉ cho READ/WRITE khi replica đã Covers(required).
7. Nếu không lấy được write-set, STALL rồi thử lại đến khi context hết hạn.

READ/WRITE cũng mang Required và replica kiểm tra **ngay trong khóa bảo vệ
state**, không chỉ tin kết quả STATE trước đó.

Sau READ thành công:

```text
V_read = max(V_read, reply.Clock)
```

Sau WRITE ACK:

```text
V_write = max(V_write, reply.Clock)
```

Write cũng phải thỏa vector đọc/ghi cũ trước khi tạo write mới. Vì vậy không
làm mất những phụ thuộc session khi client chuyển replica để ghi.
Một thao tác thất bại không tiến vector session.

## 6. Replica không được “sửa clock cho đẹp”

Trong `applyBatch()`, mỗi write chỉ sẵn sàng khi:

```text
clock Covers(write.Deps)
clock[origin] + 1 == write.Seq
```

Batch có thể tới không đúng thứ tự; vòng lặp tìm write sẵn sàng, thử trên
clock tạm, rồi mới áp dụng toàn bộ. Không tìm được tiến triển thì batch
bị từ chối, state không bị áp dụng nửa chừng.

Ví dụ nhận S1:2 nhưng chưa có S1:1: phải từ chối, không được gán clock[0]=2.
Nếu clock bị nhảy như vậy, proxy sẽ tưởng replica có dữ liệu nó thực ra thiếu.

Write trùng cùng ID/nội dung là an toàn. Write cùng ID khác nội dung là lỗi.

Khi hai origin ghi cùng key độc lập, demo chọn một thứ tự xác định:
tổng timestamp nhân quả, rồi origin, rồi seq. Timestamp tăng theo nhân quả,
tie-break xử lý các write concurrent để các replica có cùng tập write sẽ
trả cùng value. **Thứ tự register này không thay thế phép Covers của vector.**

## 7. TCP và replication nền

| Request | Chức năng |
|---|---|
| STATE | Đọc clock hiện tại |
| EXPORT | Trả các write thuộc prefix Required |
| APPLY | Áp dụng write-set với kiểm tra causal dependencies |
| READ | Kiểm tra Required rồi đọc key |
| WRITE | Kiểm tra Required rồi tạo write tại origin tương ứng |

Mỗi RPC mở một TCP connection, gửi một JSON, nhận một JSON rồi đóng.
Hạn kết nối và trao đổi hữu hạn; context được kiểm tra giữa các RPC nên
deadline session không phải hard real-time cho từng syscall.

Replication nền có chu kỳ cấu hình bằng `-delay`: lấy write-set của các
replica qua TCP, gộp và áp dụng sang các replica khác. Khoảng đợi giữa các
chu kỳ tạo divergence có thể quan sát. Đây là mô phỏng chậm sao chép ở tầng
ứng dụng, không phải cấu hình độ trễ của hệ điều hành hay mạng WAN.

## 8. Đọc code theo thứ tự

| File | Nên xem gì? |
|---|---|
| main.go | demo(): Alice/RYW rồi Bob/MR, kiểm tra và ghi trace |
| proxy.go | Session, Read, Write, ensure; Cluster và replication nền |
| replica.go | Vector, Write, applyBatch, handle các request |
| network.go | TCP client/server, deadline và đóng kết nối |
| session_test.go | Các ca đúng/sai và tình huống nguồn write-set không khả dụng |
| DESIGN.md | Tóm tắt thiết kế và đối chiếu rubric |

Một mutex trong Session giữ thứ tự thao tác của **cùng client**.
Mutex replica giữ clock/history/value nhất quán. Logger giữ từng dòng
trace không bị chen ký tự. Khi dừng cluster, hủy replication, đợi worker
rồi mới đóng server.

## 9. Kiểm thử đáng chú ý

- RYW tại replica chưa có write.
- MR với client chỉ đọc, không dựa vào V_write.
- Vector incomparable phải được repair bằng max từng thành phần.
- Nguồn write-set không khả dụng: lỗi/timeout thay vì trả stale value.
- Thiếu causal prefix và ID xung đột bị từ chối.
- Hai session không làm rò vector cho nhau.
- Nhiều lời gọi trong một session vẫn tuần tự, không race.
- Replication nền tự hội tụ.
- Write concurrent áp dụng khác thứ tự vẫn chọn cùng value.

## 10. Giới hạn

Ba replica là ba server/goroutine trong một process Go. Dữ liệu và session
ở RAM, không có WAL hoặc khôi phục sau process crash. Write-set được giữ
toàn bộ để dễ kiểm tra; chưa có garbage collection hoặc tối ưu delta.

Không tuyên bố linearizability hay strong consistency cho mọi client.
Proxy có thể phải chặn khi lịch sử cần thiết không còn truy cập được:
trả lỗi thay vì vi phạm RYW/MR là chủ ý của demo.
