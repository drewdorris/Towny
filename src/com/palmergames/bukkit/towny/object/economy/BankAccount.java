package com.palmergames.bukkit.towny.object.economy;

import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.TownyEconomyHandler;
import com.palmergames.bukkit.towny.TownyMessaging;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.TownyUniverse;
import com.palmergames.bukkit.towny.exceptions.EconomyException;
import com.palmergames.bukkit.towny.object.Government;
import com.palmergames.bukkit.towny.object.Town;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

/**
 * A variant of an account that implements
 * a checked cap on it's balance, as well as a 
 * debt system.
 */
public class BankAccount extends Account {
	
	private static final long CACHE_TIMEOUT = TownySettings.getCachedBankTimeout();
	private double balanceCap;
	private double debtCap;
	private CachedBalance cachedBalance = null; 

	public BankAccount(UUID uuid, World world, double balanceCap) {
		super(uuid, world);
		this.balanceCap = balanceCap;
		try {
			this.cachedBalance = new CachedBalance(getHoldingBalance());
		} catch (EconomyException e) {}
	}


	/**
	 * Sets the max amount of money allowed in this account.
	 * 
	 * @param balanceCap The max amount allowed in this account.
	 */
	public void setBalanceCap(double balanceCap) {
		this.balanceCap = balanceCap;
	}

	/**
	 * Sets the maximum amount of money this account can have.
	 *
	 * @return the max amount allowed in this account.
	 */
	public double getBalanceCap() {
		return balanceCap;
	}

	/**
	 * The maximum amount of debt this account can have.
	 * 
	 * Can be overriden by the config debt_cap override.
	 * Can be overriden by the config debt_cap maximum. 
	 * 
	 * @return The max amount of debt for this account.
	 */
	public double getDebtCap() {
		if (TownySettings.isDebtCapDeterminedByTownLevel()) { // town_level debtCapModifier * debt_cap.override.
			String townName = TownyUniverse.getInstance().getTown(getUUID()).getName();
			Town town = getTown();
			
			// For whatever reason, this just errors and continues
			if (town == null) {
				TownyMessaging.sendErrorMsg(String.format("Error fetching debt cap for town %s because town is not registered!", townName));
			}
			
			return Double.parseDouble(TownySettings.getTownLevel(town).get(TownySettings.TownLevel.DEBT_CAP_MODIFIER).toString()) * TownySettings.getDebtCapOverride();
		}
		
		if (TownySettings.getDebtCapOverride() != 0.0)
			return TownySettings.getDebtCapOverride();
		
		if (TownySettings.getDebtCapMaximum() != 0.0)
			return Math.min(debtCap, TownySettings.getDebtCapMaximum());
		
		return debtCap;
	}

	/**
	 * Sets the maximum amount of debt this account can have.
	 * 
	 * @param debtCap The new cap for debt on this account.
	 */
	public void setDebtCap(double debtCap) {
		this.debtCap = debtCap;
	}

	@Override
	protected boolean subtractMoney(double amount) {
		try {
			if (isBankrupt() && (getTownDebt() + amount > getDebtCap())) {
				return false;  //subtraction not allowed as it would exceed the debt cap
			}

			if (isBankrupt()) {
				return addDebt(amount);
			}

			if (!canPayFromHoldings(amount)) {

				// Calculate debt.
				double amountInDebt = amount - getHoldingBalance();

				if(amountInDebt <= getDebtCap()) {
					// Empty out account.
					boolean success = TownyEconomyHandler.setBalance(getGov(), 0, world);
					success &= addDebt(amountInDebt);

					return success;
				} else {
					return false; //Subtraction not allowed as it would exceed the debt cap
				}
			}
		} catch (EconomyException e) {
			e.printStackTrace();
		}

		// Otherwise continue like normal.
		return TownyEconomyHandler.subtract(getGov(), amount, world);
	}

	@Override
	protected boolean addMoney(double amount) {
		try {
			
			// Check balance cap.
			if (balanceCap != 0 && (getHoldingBalance() + amount > balanceCap)) {
				return false;
			}
			
			if (isBankrupt()) {
				return removeDebt(amount);
			}
		} catch (EconomyException e) {
			e.printStackTrace();
		}

		// Otherwise continue like normal.
		return TownyEconomyHandler.add(getGov(), amount, world);
	}

	/**
	 * Whether the account is in debt or not.
	 * 
	 * @return true if in debt, false otherwise.
	 */
	public boolean isBankrupt() {
		if (isTownAccount())
			return getTown().isBankrupt();
		return false;
	}
	
	/**
	 * Adds debt to a Town debtBalance
	 * @param amount the amount to add to the debtBalance.
	 * @return true if the BankAccount is a town bank account.
	 */
	private boolean addDebt(double amount) throws EconomyException {
		if (isTownAccount()) {
			setTownDebt(getTownDebt() + amount);
			return true;
		}
		return false;
	}
	
	/**
	 * @param amount the amount to remove from the debtBalance.
	 * @return true always.
	 */
	private boolean removeDebt(double amount) throws EconomyException {
		if (getTownDebt() < amount) {
			// Calculate money to go into regular account.
			double netMoney = amount - getTownDebt();
			//Clear debt account
			setTownDebt(0.0);
			//Set positive balance in regular account
			TownyEconomyHandler.setBalance(getGov(), netMoney, world);
			return true;
		} else {
			setTownDebt(getTownDebt() - amount);
			return true;
		}
	}

	@Override
	public double getHoldingBalance() throws EconomyException {
		try {
			if (isBankrupt()) {
				return getTownDebt() * -1;
			}
			return TownyEconomyHandler.getBalance(getGov(), getBukkitWorld());
		} catch (NoClassDefFoundError e) {
			e.printStackTrace();
			throw new EconomyException("Economy error getting holdings for " + getUUID());
		}
	}

	@Override
	public String getHoldingFormattedBalance() {
		try {
			if (isBankrupt()) {
				return "-" + TownyEconomyHandler.getFormattedBalance(getTownDebt());
			}
			return TownyEconomyHandler.getFormattedBalance(getHoldingBalance());
		} catch (EconomyException e) {
			return "Error";
		}
	}

	@Override
	public void removeAccount() {
		TownyEconomyHandler.removeAccount(getGov());
	}

	private class CachedBalance {
		private double balance;
		private long time;
		
		private CachedBalance(double _balance) {
			balance = _balance;
			time = System.currentTimeMillis();
		}
		
		double getBalance() {
			return balance;
		}
		private long getTime() {
			return time;
		}
		
		void setBalance(double _balance) {
			balance = _balance;
			time = System.currentTimeMillis();
		}
	}
	
	/**
	 * Returns a cached balance of a town or nation bank account,
	 * the value of which can be brand new or up to 10 minutes old 
	 * (time configurable in the config,) based on whether the 
	 * cache has been checked recently.
	 * 
	 * @return a cached balance of a town or nation bank account.
	 */
	public double getCachedBalance() {
		if (System.currentTimeMillis() - cachedBalance.getTime() > CACHE_TIMEOUT) {			
			if (!TownySettings.isEconomyAsync()) // Some economy plugins don't handle things async, 
				try {                            // luckily we have a config option for this such case.
					cachedBalance.setBalance(getHoldingBalance());
				} catch (EconomyException e1) {
					e1.printStackTrace();
				}
			else 
				Bukkit.getScheduler().runTaskAsynchronously(Towny.getPlugin(), () -> {
					try {
						cachedBalance.setBalance(getHoldingBalance());
					} catch (EconomyException e) {
						e.printStackTrace();
					}
				});				
		}
		return cachedBalance.getBalance();
	}

	/**
	 * return true if this BankAcount is one belonging to a Town.
	 */
	private boolean isTownAccount() {
		return TownyUniverse.getInstance().getTown(getUUID()) != null;
	}
	
	/**
	 * @return town or null, if this BankAccount does not belong to a town.
	 */
	@Nullable
	private Town getTown() {
		Town town = null;
		if (isTownAccount()) 
			town = TownyUniverse.getInstance().getTown(getUUID());
		return town;
	}
	
	private double getTownDebt() {
		return getTown().getDebtBalance();
	}
	
	private void setTownDebt(double amount) {
		getTown().setDebtBalance(amount);
		getTown().save();
	}


	@Override
	public String getName() {
		if (isTownAccount())
			return getTown().getName();
		else return TownyUniverse.getInstance().getNation(getUUID()).getName();
	}
	

	private Government getGov() {
		if (isTownAccount())
			return getTown();
		else return TownyUniverse.getInstance().getNation(getUUID());
	}

}
