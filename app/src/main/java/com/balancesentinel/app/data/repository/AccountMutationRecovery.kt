package com.balancesentinel.app.data.repository

/** Durable recovery hook invoked during application startup. */
interface AccountMutationRecovery {
    suspend fun recover(): AccountMutationResult.Recovered
}

/** No-op compatibility implementation used until Room recovery is wired. */
class NoOpAccountMutationRecovery : AccountMutationRecovery {
    override suspend fun recover(): AccountMutationResult.Recovered =
        AccountMutationResult.Recovered(emptyList())
}

/** Startup adapter for the Room-backed coordinator. */
class RoomAccountMutationRecovery(
    private val coordinator: RoomAccountMutationCoordinator
) : AccountMutationRecovery {
    override suspend fun recover(): AccountMutationResult.Recovered = coordinator.recover()
}
