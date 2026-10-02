-- 40 fictional participants in 15 countries. BICs use the synthetic prefix
-- XB01..XB40 and do not identify real institutions. Net debit caps are in USD
-- cents (tier 1: 20,000,000.00; tier 2: 8,000,000.00; tier 3: 3,000,000.00).
insert into participant (id, bic, name, country, currency, net_debit_cap) values
    (1, 'XB01USNY', 'Simulated Bank US-1 (New York)', 'US', 'USD', 2000000000),
    (2, 'XB02USNY', 'Simulated Bank US-2 (New York)', 'US', 'USD', 800000000),
    (3, 'XB03USNY', 'Simulated Bank US-3 (New York)', 'US', 'USD', 300000000),
    (4, 'XB04USNY', 'Simulated Bank US-4 (New York)', 'US', 'USD', 300000000),
    (5, 'XB05USNY', 'Simulated Bank US-5 (New York)', 'US', 'USD', 300000000),
    (6, 'XB06GB2L', 'Simulated Bank GB-1 (London)', 'GB', 'GBP', 2000000000),
    (7, 'XB07GB2L', 'Simulated Bank GB-2 (London)', 'GB', 'GBP', 800000000),
    (8, 'XB08GB2L', 'Simulated Bank GB-3 (London)', 'GB', 'GBP', 300000000),
    (9, 'XB09GB2L', 'Simulated Bank GB-4 (London)', 'GB', 'GBP', 300000000),
    (10, 'XB10DEFF', 'Simulated Bank DE-1 (Frankfurt)', 'DE', 'EUR', 2000000000),
    (11, 'XB11DEFF', 'Simulated Bank DE-2 (Frankfurt)', 'DE', 'EUR', 800000000),
    (12, 'XB12DEFF', 'Simulated Bank DE-3 (Frankfurt)', 'DE', 'EUR', 300000000),
    (13, 'XB13FRPP', 'Simulated Bank FR-1 (Paris)', 'FR', 'EUR', 2000000000),
    (14, 'XB14FRPP', 'Simulated Bank FR-2 (Paris)', 'FR', 'EUR', 800000000),
    (15, 'XB15NL2A', 'Simulated Bank NL-1 (Amsterdam)', 'NL', 'EUR', 2000000000),
    (16, 'XB16NL2A', 'Simulated Bank NL-2 (Amsterdam)', 'NL', 'EUR', 800000000),
    (17, 'XB17CHZZ', 'Simulated Bank CH-1 (Zurich)', 'CH', 'CHF', 2000000000),
    (18, 'XB18CHZZ', 'Simulated Bank CH-2 (Zurich)', 'CH', 'CHF', 800000000),
    (19, 'XB19INBB', 'Simulated Bank IN-1 (Mumbai)', 'IN', 'INR', 2000000000),
    (20, 'XB20INBB', 'Simulated Bank IN-2 (Mumbai)', 'IN', 'INR', 800000000),
    (21, 'XB21INBB', 'Simulated Bank IN-3 (Mumbai)', 'IN', 'INR', 300000000),
    (22, 'XB22INBB', 'Simulated Bank IN-4 (Mumbai)', 'IN', 'INR', 300000000),
    (23, 'XB23MXMM', 'Simulated Bank MX-1 (Mexico City)', 'MX', 'MXN', 2000000000),
    (24, 'XB24MXMM', 'Simulated Bank MX-2 (Mexico City)', 'MX', 'MXN', 800000000),
    (25, 'XB25MXMM', 'Simulated Bank MX-3 (Mexico City)', 'MX', 'MXN', 300000000),
    (26, 'XB26CATT', 'Simulated Bank CA-1 (Toronto)', 'CA', 'CAD', 2000000000),
    (27, 'XB27CATT', 'Simulated Bank CA-2 (Toronto)', 'CA', 'CAD', 800000000),
    (28, 'XB28BRSP', 'Simulated Bank BR-1 (Sao Paulo)', 'BR', 'BRL', 2000000000),
    (29, 'XB29BRSP', 'Simulated Bank BR-2 (Sao Paulo)', 'BR', 'BRL', 800000000),
    (30, 'XB30SGSG', 'Simulated Bank SG-1 (Singapore)', 'SG', 'SGD', 2000000000),
    (31, 'XB31SGSG', 'Simulated Bank SG-2 (Singapore)', 'SG', 'SGD', 800000000),
    (32, 'XB32SGSG', 'Simulated Bank SG-3 (Singapore)', 'SG', 'SGD', 300000000),
    (33, 'XB33HKHH', 'Simulated Bank HK-1 (Hong Kong)', 'HK', 'HKD', 2000000000),
    (34, 'XB34HKHH', 'Simulated Bank HK-2 (Hong Kong)', 'HK', 'HKD', 800000000),
    (35, 'XB35JPJT', 'Simulated Bank JP-1 (Tokyo)', 'JP', 'JPY', 2000000000),
    (36, 'XB36JPJT', 'Simulated Bank JP-2 (Tokyo)', 'JP', 'JPY', 800000000),
    (37, 'XB37AU2S', 'Simulated Bank AU-1 (Sydney)', 'AU', 'AUD', 2000000000),
    (38, 'XB38AU2S', 'Simulated Bank AU-2 (Sydney)', 'AU', 'AUD', 800000000),
    (39, 'XB39CNSH', 'Simulated Bank CN-1 (Shanghai)', 'CN', 'CNY', 2000000000),
    (40, 'XB40CNSH', 'Simulated Bank CN-2 (Shanghai)', 'CN', 'CNY', 800000000);

insert into participant_position (participant_id) select id from participant;

insert into clearing_cycle (business_date, status) values (current_date, 'OPEN');
