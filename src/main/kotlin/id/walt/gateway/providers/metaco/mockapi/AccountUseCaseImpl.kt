package id.walt.gateway.providers.metaco.mockapi

import id.walt.gateway.Common
import id.walt.gateway.dto.AmountWithValue
import id.walt.gateway.dto.TransferData
import id.walt.gateway.dto.accounts.AccountData
import id.walt.gateway.dto.accounts.AccountIdentifier
import id.walt.gateway.dto.accounts.AccountInitiationParameter
import id.walt.gateway.dto.balances.AccountBalance
import id.walt.gateway.dto.balances.BalanceData
import id.walt.gateway.dto.balances.BalanceParameter
import id.walt.gateway.dto.profiles.ProfileData
import id.walt.gateway.dto.profiles.ProfileParameter
import id.walt.gateway.dto.requests.RequestResult
import id.walt.gateway.dto.tickers.TickerParameter
import id.walt.gateway.dto.transactions.TransactionData
import id.walt.gateway.dto.transactions.TransactionListParameter
import id.walt.gateway.dto.transactions.TransactionParameter
import id.walt.gateway.dto.transactions.TransactionTransferData
import id.walt.gateway.usecases.AccountUseCase
import id.walt.gateway.usecases.TickerUseCase
import java.time.Duration
import java.time.Instant
import java.util.*


class AccountUseCaseImpl(
    private val tickerUseCase: TickerUseCase,
) : AccountUseCase {
    private val accountsPool = (1..5).map { getAccount() }

    override fun profile(parameter: ProfileParameter) = Result.success(getProfile(parameter.id))
    override fun create(parameter: AccountInitiationParameter): Result<RequestResult> =
        Result.success(RequestResult(true, "0x${Common.getRandomString(40, 2)}"))

    override fun balance(parameter: ProfileParameter) = Result.success(AccountBalance((1..2).map { getBalance() }))
    override fun balance(parameter: BalanceParameter) = Result.success(getBalance())

    override fun transactions(parameter: TransactionListParameter) =
        Result.success((1..24).map { getTransaction(UUID.randomUUID().toString()) }.sortedByDescending { Instant.parse(it.date) })

    override fun transaction(parameter: TransactionParameter) = Result.success(getTransactionTransferData())

    private fun getProfile(id: String) = ProfileData(
        profileId = UUID.randomUUID().toString(),
        accounts = accountsPool,
    )
    private fun getAccount() = AccountData(
        accountIdentifier = AccountIdentifier(UUID.randomUUID().toString(), UUID.randomUUID().toString()),
        alias = Common.getRandomString(7, 1),
        addresses = listOf("0x${Common.getRandomString(40, 2)}"),
        tickers = (1..5).map { tickerUseCase.get(TickerParameter("")).getOrThrow().id }.distinct()
    )

    private fun getBalance() = BalanceData(
        amount = Common.getRandomString(Common.getRandomInt(from = 3, to = 16), 0).removePrefix("0"),
        ticker = tickerUseCase.get(TickerParameter("")).getOrThrow(),
    )

    private fun getTransaction(id: String) = tickerUseCase.get(TickerParameter("")).getOrThrow().let {
        TransactionData(
            id = id,
            relatedAccount = "0x${Common.getRandomString(40, 2)}",
            amount = Common.getRandomString(3, 0),
            ticker = it,
            price = it.price,
            type = listOf("Transfer", "Sell", "Buy", "Receive")[Common.getRandomInt(to = 4)],
            status = getTransactionStatus(),
            date = getDate(),
        )
    }

    private fun getTokenTriple() = let {
        val name = listOf("tGOLD", "Stable Coin")[Common.getRandomInt(to = 2)]
        val kind = if (name == "tGOLD") "Contract" else "Native"
        val symbol = if (name == "tGOLD") "tGOLD" else "eth"
        Triple(name, kind, symbol)
    }

    private fun getTransfers() = (1..2).map {
        TransferData(
            amount = Common.getRandomLong(to = 10000).toString(),
            type = listOf("Transfer", "Fee")[it % 2],
            address = listOf("0x${Common.getRandomString(40, 2)}", null)[it % 2]
        )
    }

    private fun getTransactionTransferData() = TransactionTransferData(
        status = getTransactionStatus(),
        date = getDate(),
        total = getAmountWithValue(),
        transfers = getTransfers()
    )

    private fun getTransactionStatus() = listOf("Detected", "Confirmed", "Expired")[Common.getRandomInt(to = 3)]

    private fun getDate() = Instant.now().plus(Duration.ofDays(Common.getRandomLong(from = -10, to = 0))).toString()

    private fun getAmountWithValue() = AmountWithValue(
        amount = Common.getRandomLong(to = 10000).toString(),
        ticker = tickerUseCase.get(TickerParameter("")).getOrThrow()
    )
}