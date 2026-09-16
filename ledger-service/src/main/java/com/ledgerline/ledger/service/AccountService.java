package com.ledgerline.ledger.service;

import com.ledgerline.ledger.domain.Account;
import com.ledgerline.ledger.domain.AccountBalance;
import com.ledgerline.ledger.domain.AccountStatus;
import com.ledgerline.ledger.domain.AuditLogEntry;
import com.ledgerline.ledger.dto.AccountResponse;
import com.ledgerline.ledger.dto.CreateAccountRequest;
import com.ledgerline.ledger.dto.PostingDto;
import com.ledgerline.ledger.exception.AccountNotFoundException;
import com.ledgerline.ledger.repository.AccountBalanceRepository;
import com.ledgerline.ledger.repository.AccountRepository;
import com.ledgerline.ledger.repository.AuditLogRepository;
import com.ledgerline.ledger.repository.PostingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final AccountBalanceRepository balanceRepository;
    private final PostingRepository postingRepository;
    private final AuditLogRepository auditLogRepository;

    public AccountService(
        AccountRepository accountRepository,
        AccountBalanceRepository balanceRepository,
        PostingRepository postingRepository,
        AuditLogRepository auditLogRepository
    ) {
        this.accountRepository = accountRepository;
        this.balanceRepository = balanceRepository;
        this.postingRepository = postingRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        UUID accountId = UUID.randomUUID();
        Instant now = Instant.now();
        String currency = request.currency().trim().toUpperCase();

        Account account = new Account(
            accountId,
            currency,
            request.type(),
            AccountStatus.ACTIVE,
            now,
            0L
        );
        accountRepository.createAccount(account);

        AccountBalance balance = new AccountBalance(
            accountId,
            0L,
            request.type(),
            0L
        );
        balanceRepository.createBalance(balance);

        auditLogRepository.insertLog(new AuditLogEntry(
            null,
            "SYSTEM",
            "ACCOUNT_CREATED",
            accountId.toString(),
            now,
            "{\"currency\":\"" + currency + "\",\"type\":\"" + request.type() + "\"}"
        ));

        return new AccountResponse(
            accountId,
            currency,
            request.type(),
            AccountStatus.ACTIVE,
            now,
            0L
        );
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID accountId) {
        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new AccountNotFoundException(accountId));

        AccountBalance balance = balanceRepository.findById(accountId)
            .orElseThrow(() -> new AccountNotFoundException(accountId));

        return new AccountResponse(
            account.id(),
            account.currency(),
            account.type(),
            account.status(),
            account.createdAt(),
            balance.balanceMinor()
        );
    }

    @Transactional(readOnly = true)
    public List<PostingDto> getPostings(UUID accountId, int limit, int offset) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }

        return postingRepository.findByAccountId(accountId, limit, offset).stream()
            .map(p -> new PostingDto(
                p.id(),
                p.accountId(),
                p.amountMinor(),
                p.currency(),
                p.createdAt()
            ))
            .toList();
    }
}
