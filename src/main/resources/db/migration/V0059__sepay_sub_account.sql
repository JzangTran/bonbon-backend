-- Some banks linked through SePay (VietinBank personal accounts among them) report a transfer only when its content starts with
-- "SEVQR TKP<sub-account>": the shop's SePay sub-account (VA) code is kept so the VietQR content bonbon builds carries it.
alter table payments.sepay_connections add column sub_account varchar(10);
